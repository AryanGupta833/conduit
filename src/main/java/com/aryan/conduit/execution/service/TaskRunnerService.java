
package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.*;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.retry.RetryPolicy;
import com.aryan.conduit.execution.retry.RetryPolicyFactory;
import com.aryan.conduit.execution.retry.TaskLeaseHeartbeat;
import com.aryan.conduit.execution.retry.TaskLeaseLostException;
import com.aryan.conduit.execution.retry.TaskAlreadyInProgressException;
import com.aryan.conduit.execution.retry.TaskTimeoutException;
import com.aryan.conduit.execution.retry.TaskExecutionCancelledException;
import com.aryan.conduit.execution.workspace.ExecutionWorkspaceService;
import com.aryan.conduit.plugin.PluginManager;
import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import com.aryan.conduit.observability.ConduitMetrics;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

@Service
public class TaskRunnerService {

    private static final ExecutorService ATTEMPT_EXECUTOR = new ThreadPoolExecutor(
            8, 8, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
            runnable -> {
                Thread thread = new Thread(runnable, "plugin-attempt");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskExecutionService taskExecutionService;
    private final ExecutionLogService executionLogService;
    private final PluginManager pluginManager;
    private final RetryPolicyFactory retryPolicyFactory;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final ConfigurationResolver configurationResolver;
    private final ConduitMetrics metrics;
    private final ExpressionContextService expressionContextService;
    private final ExecutionWorkspaceService workspaceService;

    @Autowired(required = false)
    private com.aryan.conduit.workflow.service.CredentialReferenceService credentialReferenceService;

    @Value("${conduit.execution-context.max-task-result-bytes:262144}")
    private int maximumTaskResultBytes = 262_144;

    @Autowired
    public TaskRunnerService(TaskExecutionRepository taskExecutionRepository,
                             TaskExecutionService taskExecutionService,
                             ExecutionLogService executionLogService,
                             TaskOutputService taskOutputService,
                             PluginManager pluginManager,
                             RetryPolicyFactory retryPolicyFactory,
                             IdempotencyService idempotencyService,
                             ObjectMapper objectMapper,
                             ConfigurationResolver configurationResolver,
                             ConduitMetrics metrics,
                             ExpressionContextService expressionContextService,
                             ExecutionWorkspaceService workspaceService) {
        this.taskExecutionRepository = taskExecutionRepository;
        this.taskExecutionService = taskExecutionService;
        this.executionLogService = executionLogService;
        this.pluginManager = pluginManager;
        this.retryPolicyFactory = retryPolicyFactory;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.configurationResolver = configurationResolver;
        this.metrics = metrics;
        this.expressionContextService = expressionContextService;
        this.workspaceService = workspaceService;
    }

    /** Compatibility constructor retained for existing direct unit-test and SDK integrations. */
    public TaskRunnerService(TaskExecutionRepository taskExecutionRepository,
                             TaskExecutionService taskExecutionService,
                             ExecutionLogService executionLogService,
                             TaskOutputService taskOutputService,
                             PluginManager pluginManager,
                             RetryPolicyFactory retryPolicyFactory,
                             IdempotencyService idempotencyService,
                             ObjectMapper objectMapper,
                             ConfigurationResolver configurationResolver,
                             ConduitMetrics metrics,
                             ExpressionContextService expressionContextService) {
        this(taskExecutionRepository, taskExecutionService, executionLogService, taskOutputService,
                pluginManager, retryPolicyFactory, idempotencyService, objectMapper,
                configurationResolver, metrics, expressionContextService,
                new ExecutionWorkspaceService("./conduit-workspaces", 1_073_741_824L));
    }


    private Map<String, Object> executeTask(Long taskExecutionId)
            throws InterruptedException {

        return executeTaskInternal(taskExecutionId);
    }


    private Map<String, Object> executeTaskInternal(
            Long taskExecutionId)
            throws InterruptedException {

        executionLogService.log(
                taskExecutionId,
                LogLevel.INFO,
                "Task Started"
        );

        TaskExecution taskExecution =
                taskExecutionRepository.findById(taskExecutionId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Task execution not found: "
                                                + taskExecutionId
                                )
                        );

        String idempotencyKey =
                taskExecution.getIdempotencyKey();

        /*
         * First check whether this logical task
         * has already completed successfully.
         */
        var completedResult =
                idempotencyService.getCompletedResult(idempotencyKey);

        if (completedResult.isPresent()) {

            executionLogService.log(
                    taskExecutionId,
                    LogLevel.INFO,
                    "Returning previously completed idempotent result"
            );

            return pluginResultToMap(completedResult.get());
        }

        /*
         * Try to acquire ownership.
         *
         * If successful, we receive the lease token
         * identifying this worker's ownership.
         */
        Optional<UUID> leaseToken =
                idempotencyService.tryStart(idempotencyKey);

        /*
         * IMPORTANT:
         *
         * Do NOT start the heartbeat before confirming
         * ownership.
         */
        if (leaseToken.isEmpty()) {

            /*
             * Another worker may have completed the task
             * between our first check and tryStart().
             */
            completedResult =
                    idempotencyService.getCompletedResult(idempotencyKey);

            if (completedResult.isPresent()) {

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.INFO,
                        "Another execution completed this task; "
                                + "using stored idempotent result"
                );

                return pluginResultToMap(completedResult.get());
            }

            /*
             * Another worker currently owns the task.
             */
            throw new TaskAlreadyInProgressException(idempotencyKey);
        }

        /*
         * We own the task.
         *
         * This token uniquely identifies our lease.
         */
        UUID token = leaseToken.get();

        /*
         * Only now should the heartbeat start.
         */
        TaskLeaseHeartbeat heartbeat =
                new TaskLeaseHeartbeat(
                        idempotencyService,
                        idempotencyKey,
                        token
                );

        heartbeat.start(
                20,
                20,
                TimeUnit.SECONDS
        );

        /*
         * Idempotency ownership was successfully acquired.
         */
        try {

            TaskExecutionService.StartDecision startDecision =
                    taskExecutionService.tryMarkRunning(taskExecutionId);
            if (startDecision == TaskExecutionService.StartDecision.DEFERRED) {
                throw new TaskAlreadyInProgressException("Workflow execution is paused");
            }
            if (startDecision == TaskExecutionService.StartDecision.CANCELLED) {
                throw new TaskExecutionCancelledException("Workflow execution is no longer running");
            }

            String pluginType =
                    taskExecution.getTaskNode().getPluginType();

            WorkflowPlugin plugin =
                    pluginManager.getPlugin(pluginType);

            executionLogService.log(
                    taskExecutionId,
                    LogLevel.INFO,
                    "Executing plugin: " + pluginType
            );

            String configurationJson =
                    taskExecution
                            .getTaskNode()
                            .getConfigurationJson();

            String resolvedConfiguration =
                    configurationResolver.resolve(
                            taskExecution
                                    .getWorkflowExecution()
                                    .getId(),
                            configurationJson
                    );

            // Persist the actual expression-resolved inputs for later inspection.
            // The details API redacts sensitive fields before returning this data.
            taskExecution.setResolvedConfigurationJson(resolvedConfiguration);
            taskExecutionRepository.save(taskExecution);

            TaskNode runtimeTaskNode =
                    createRuntimeTaskNode(
                            taskExecution.getTaskNode(),
                            resolvedConfiguration
                    );

            Map<String, Object> pluginConfiguration = parsePluginConfiguration(
                    pluginType, resolvedConfiguration);
            // References remain persisted as markers; plaintext enters memory only immediately before plugin invocation.
            if (credentialReferenceService != null) {
                pluginConfiguration = credentialReferenceService.resolveReferences(
                        taskExecution.getWorkflowExecution().getWorkflowVersion().getWorkflow().getId(), pluginConfiguration,
                        taskExecution.getWorkflowExecution().getInitiatedBy(), runtimeTaskNode.getName());
            }
            pluginConfiguration = configureWorkspace(pluginType, pluginConfiguration,
                    taskExecution.getWorkflowExecution().getId());
            try {
                plugin.validateConfiguration(pluginConfiguration);
            } catch (PluginConfigurationException e) {
                String message = "Invalid configuration for plugin '" + pluginType + "': " + e.getMessage();
                if (credentialReferenceService != null) message = credentialReferenceService.redactMessage(
                        taskExecution.getWorkflowExecution().getWorkflowVersion().getWorkflow().getId(), message);
                throw new IllegalArgumentException(message);
            }

            Map<String, Object> variables = expressionContextService.buildContext(
                    taskExecution.getWorkflowExecution().getId());
            com.aryan.conduit.execution.workspace.ExecutionWorkspace executionWorkspace;
            try {
                executionWorkspace = workspaceService.forExecution(taskExecution.getWorkflowExecution().getId().toString());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Unable to prepare execution workspace", e);
            }
            PluginContext pluginContext = new PluginContext(
                    runtimeTaskNode.getName(), runtimeTaskNode.getTimeoutSeconds(), pluginConfiguration, variables,
                    executionWorkspace, taskExecution.getIdempotencyKey());
            PluginResult result = plugin.execute(pluginContext);
            long workspaceBytes = executionWorkspace.usedBytes();
            if (workspaceBytes > executionWorkspace.maximumBytes())
                throw new com.aryan.conduit.execution.workspace.WorkspaceLimitException(
                        "Execution workspace size limit exceeded (" + workspaceBytes + " bytes)");
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Plugin execution was interrupted");
            }
            if (result == null) {
                throw new IllegalStateException("Plugin '" + pluginType + "' returned no result");
            }
            if (credentialReferenceService != null) {
                result = credentialReferenceService.redactResult(
                        taskExecution.getWorkflowExecution().getWorkflowVersion().getWorkflow().getId(), result);
            }
            Map<String, Object> resultMetadata = result.getMetadata();
            if (Boolean.TRUE.equals(resultMetadata.get("outputTruncated"))) metrics.pluginOutputTruncated(pluginType);
            Object bytes = resultMetadata.get("sizeBytes");
            if (bytes instanceof Number number) {
                String operation = String.valueOf(resultMetadata.getOrDefault("operation", "")).toUpperCase(java.util.Locale.ROOT);
                metrics.pluginBytes(pluginType, List.of("READ", "DOWNLOAD", "GET").contains(operation) ? "read" : "written", number.longValue());
            }

            /*
             * IMPORTANT:
             *
             * Never commit a result if our lease was lost.
             */
            if (heartbeat.isLeaseLost()) {

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.ERROR,
                        "Task lease lost before completion"
                );

                throw new TaskLeaseLostException(
                        "Idempotency lease lost for task: "
                                + taskExecutionId
                );
            }

            /*
             * Plugin itself reported failure.
             */
            if (!result.isSuccess()) {

                RuntimeException pluginFailure = new RuntimeException(result.getOutput());
                logSafely(taskExecutionId, LogLevel.ERROR,
                        "Plugin execution failed: " + result.getOutput(), pluginFailure);

                /*
                 * Only release if we still own the lease.
                 *
                 * The token protects us from accidentally
                 * releasing another worker's lease.
                 */
                if (!heartbeat.isLeaseLost()) {
                    try {
                        idempotencyService.release(idempotencyKey, token);
                    } catch (RuntimeException releaseFailure) {
                        pluginFailure.addSuppressed(releaseFailure);
                    }
                }

                throw pluginFailure;
            }

            /*
             * Plugin completed successfully.
             *
             * Double-check lease ownership immediately before
             * committing the result.
             */
            if (heartbeat.isLeaseLost()) {

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.ERROR,
                        "Task lease lost before result commit"
                );

                throw new TaskLeaseLostException(
                        "Idempotency lease lost before result commit: "
                                + taskExecutionId
                );
            }

            /*
             * Serialize the PluginResult as real JSON.
             *
             * This is important because IdempotencyService later
             * deserializes resultJson using ObjectMapper.
             */
            String resultJson;

            try {

                resultJson =
                        objectMapper.writeValueAsString(result);

            } catch (JsonProcessingException e) {

                throw new IllegalStateException(
                        "Failed to serialize plugin result",
                        e
                );
            }

            if (resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maximumTaskResultBytes)
                throw new IllegalArgumentException("Task result exceeds configured execution-context size limit");

            /*
             * Store the result as COMPLETED.
             *
             * The database verifies:
             *
             * key
             * + lease token
             * + IN_PROGRESS
             * + unexpired lease
             */
            boolean completed =
                    idempotencyService.complete(
                            idempotencyKey,
                            token,
                            resultJson
                    );

            if (!completed) {

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.ERROR,
                        "Task result rejected because idempotency lease "
                                + "is no longer valid"
                );

                throw new TaskLeaseLostException(
                        "Could not complete task because lease is no longer "
                                + "valid: " + taskExecutionId
                );
            }

            executionLogService.log(
                    taskExecutionId,
                    LogLevel.INFO,
                    "Task Completed Successfully"
            );

            return pluginResultToMap(result);

        } catch (TaskLeaseLostException e) {

            /*
             * VERY IMPORTANT:
             *
             * Never call release() here.
             *
             * Another worker may already have reclaimed
             * the expired lease.
             */
            logSafely(taskExecutionId, LogLevel.ERROR,
                    "Task execution stopped because lease was lost", e);

            throw e;

        } catch (Exception e) {

            /*
             * If lease was lost, DO NOT release it.
             *
             * The lease token additionally protects against
             * releasing another worker's lease.
             */
            if (!heartbeat.isLeaseLost()) {

                try {
                    idempotencyService.release(idempotencyKey, token);
                } catch (RuntimeException releaseFailure) {
                    e.addSuppressed(releaseFailure);
                }
            }

            String safeMessage = e.getMessage();
            if (credentialReferenceService != null) safeMessage = credentialReferenceService.redactMessage(
                    taskExecution.getWorkflowExecution().getWorkflowVersion().getWorkflow().getId(), safeMessage);
            logSafely(taskExecutionId, LogLevel.ERROR, "Plugin execution failed: " + safeMessage, e);

            if (e instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            if (e instanceof RuntimeException runtimeException) {
                if (java.util.Objects.equals(safeMessage, e.getMessage())) throw runtimeException;
                throw new RuntimeException(safeMessage);
            }
            throw new RuntimeException("Plugin execution failed: " + safeMessage);

        } finally {

            /*
             * Always stop the heartbeat when this attempt
             * finishes.
             */
            heartbeat.stop();
        }
    }

    private void logSafely(Long taskExecutionId, LogLevel level, String message, Throwable primaryFailure) {
        try {
            executionLogService.log(taskExecutionId, level, message);
        } catch (RuntimeException loggingFailure) {
            if (loggingFailure != primaryFailure) primaryFailure.addSuppressed(loggingFailure);
        }
    }

    Map<String, Object> configureWorkspace(String pluginType, Map<String, Object> original, Long executionId) {
        String type = pluginType.toUpperCase(java.util.Locale.ROOT);
        boolean usesWorkspace = List.of("FILE_READ", "FILE_WRITE", "S3_UPLOAD", "S3_DOWNLOAD", "SFTP", "GIT", "DOCKER_BUILD", "SHELL", "DOCKER_RUN")
                .contains(type);
        if (!usesWorkspace) return original;
        try {
            var workspace = workspaceService.forExecution(executionId.toString());
            Map<String, Object> configuration = new HashMap<>(original);
            Object configuredRoot = configuration.get("workspaceRoot");
            java.nio.file.Path configuredWorkspaceRoot = workspace.root();
            if (configuredRoot != null) {
                String rootValue = normalizeConfiguredPath(String.valueOf(configuredRoot));
                configuredWorkspaceRoot = resolveWorkspaceValue(workspace, rootValue, false);
                if (java.nio.file.Files.exists(configuredWorkspaceRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && !java.nio.file.Files.isDirectory(configuredWorkspaceRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new PluginConfigurationException("workspaceRoot must resolve to a directory inside the managed execution workspace");
                }
                if (!java.nio.file.Files.exists(configuredWorkspaceRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    java.nio.file.Files.createDirectories(configuredWorkspaceRoot);
                    // Revalidate after creation in case a filesystem entry changed during the operation.
                    resolveWorkspaceValue(workspace, workspace.root().relativize(configuredWorkspaceRoot).toString(), false);
                }
            }
            if (type.equals("SHELL") || type.equals("DOCKER_RUN")) {
                // Container plugins receive the managed workspace through PluginContext,
                // never as a workflow-controlled host path.
                configuration.remove("workspaceRoot");
            } else {
                configuration.put("workspaceRoot", workspace.root().toString());
            }
            if (type.equals("SHELL") && configuration.get("workingDirectory") != null) {
                String working = normalizeConfiguredPath(String.valueOf(configuration.get("workingDirectory")));
                if (working.startsWith("workspace:/")) {
                    String relative = working.substring("workspace:/".length());
                    java.nio.file.Path resolved = workspace.resolve(relative.isEmpty() ? "working" : "working/" + relative);
                    configuration.put("workingDirectory", com.aryan.conduit.docker.ManagedWorkspaceMount.CONTAINER_PATH
                            + (relative.isEmpty() ? "" : "/" + workspace.working().relativize(resolved).toString().replace('\\', '/')));
                } else if (working.equals("/workspace") || working.startsWith("/workspace/")) {
                    configuration.put("workingDirectory", com.aryan.conduit.docker.ManagedWorkspaceMount.CONTAINER_PATH
                            + working.substring("/workspace".length()));
                }
            }
            for (String key : List.of("destinationPath", "contextPath", "repositoryPath", "path", "filePath", "localPath")) {
                if (key.equals("localPath") && !type.equals("SFTP")) continue;
                if (configuration.get(key) != null) {
                    if (type.equals("S3_UPLOAD") && key.equals("filePath")
                            && String.valueOf(configuration.get(key)).isBlank()) continue;
                    String configuredValue = scopeWorkspaceRelativePath(workspace, configuredWorkspaceRoot,
                            String.valueOf(configuration.get(key)));
                    boolean mustBeFile = ((type.equals("S3_UPLOAD") && key.equals("filePath"))
                            || (type.equals("SFTP") && key.equals("localPath")
                            && "UPLOAD".equalsIgnoreCase(String.valueOf(configuration.get("operation")))))
                            && !configuredValue.isBlank();
                    java.nio.file.Path resolved = resolveWorkspaceValue(workspace, configuredValue, mustBeFile);
                    boolean pluginRequiresLogicalPath = type.equals("SFTP") && key.equals("localPath");
                    configuration.put(key, mustBeFile && !pluginRequiresLogicalPath ? resolved.toString()
                            : workspace.root().relativize(resolved).toString());
                }
            }
            if (configuration.get("dockerfile") != null)
                configuration.put("dockerfile", normalizeConfiguredPath(String.valueOf(configuration.get("dockerfile"))));
            return configuration;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to prepare execution workspace", e);
        }
    }

    /** Resolve relative plugin paths under the validated workspaceRoot; logical and confined absolute paths are already rooted. */
    private String scopeWorkspaceRelativePath(
            com.aryan.conduit.execution.workspace.ExecutionWorkspace workspace,
            java.nio.file.Path configuredWorkspaceRoot,
            String configuredValue) {
        String value = normalizeConfiguredPath(configuredValue);
        if (value.startsWith("workspace:/")) return value;
        try {
            java.nio.file.Path path = java.nio.file.Path.of(value);
            if (path.isAbsolute() || configuredWorkspaceRoot.equals(workspace.root())) return value;
            return workspace.root().relativize(configuredWorkspaceRoot).resolve(path).toString();
        } catch (java.nio.file.InvalidPathException e) {
            throw new PluginConfigurationException("Invalid workspace path", e);
        }
    }

    java.nio.file.Path resolveWorkspaceValue(com.aryan.conduit.execution.workspace.ExecutionWorkspace workspace,
                                                     String value, boolean mustExist) throws java.io.IOException {
        String normalizedValue = normalizeConfiguredPath(value);
        java.nio.file.Path resolved;
        try {
            if (normalizedValue.startsWith("workspace:/")) {
                // Resolve the logical namespace before asking the platform to parse a Path.
                resolved = workspace.resolve(normalizedValue);
            } else {
                java.nio.file.Path path = java.nio.file.Path.of(normalizedValue);
                String relative = normalizedValue;
                if (path.isAbsolute()) {
                    java.nio.file.Path absolute = path.normalize();
                    if (!absolute.startsWith(workspace.root()))
                        throw new PluginConfigurationException("Path must be inside the managed execution workspace");
                    relative = workspace.root().relativize(absolute).toString();
                }
                resolved = workspace.resolve(relative);
            }
        }
        catch (IllegalArgumentException e) { throw new PluginConfigurationException(e.getMessage(), e); }
        if (mustExist && !java.nio.file.Files.isRegularFile(resolved, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new PluginConfigurationException("Workspace file does not exist or is not a regular file");
        return resolved;
    }

    /**
     * Accept a single pair of UI/editor quote delimiters at the configuration
     * boundary. JSON syntax itself is already removed by Jackson. Reject
     * unmatched or embedded quotes instead of repairing ambiguous input.
     */
    private String normalizeConfiguredPath(String value) {
        if (value == null) throw new PluginConfigurationException("Workspace path is required");
        String path = value;
        boolean startsQuoted = path.startsWith("\"");
        boolean endsQuoted = path.endsWith("\"");
        if (startsQuoted || endsQuoted) {
            if (path.length() < 2 || !startsQuoted || !endsQuoted)
                throw new PluginConfigurationException("Workspace path has malformed surrounding quotes");
            path = path.substring(1, path.length() - 1);
        }
        if (path.indexOf('"') >= 0)
            throw new PluginConfigurationException("Workspace path contains an invalid quote character");
        return path;
    }


    public Map<String,Object> executeWithRetry(
            Long taskExecutionId,
            Integer maxRetries,
            Integer timeoutSeconds)
            throws InterruptedException {

        long started = System.nanoTime();
        String status = "success";
        String pluginType = metricPluginType(taskExecutionId);
        try {
            return executeWithRetryInternal(taskExecutionId, maxRetries, timeoutSeconds, pluginType);
        } catch (TaskTimeoutException e) {
            status = "timeout";
            throw e;
        } catch (InterruptedException e) {
            status = "cancelled";
            throw e;
        } catch (RuntimeException e) {
            status = "failed";
            throw e;
        } finally {
            metrics.taskFinished(pluginType, status, System.nanoTime() - started);
        }
    }

    private String metricPluginType(Long taskExecutionId) {
        try {
            return taskExecutionRepository.findById(taskExecutionId)
                    .map(task -> task.getTaskNode() == null ? "unknown" : task.getTaskNode().getPluginType())
                    .orElse("unknown");
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private Map<String,Object> executeWithRetryInternal(
            Long taskExecutionId,
            Integer maxRetries,
            Integer timeoutSeconds,
            String pluginType)
            throws InterruptedException {

        RetryPolicy policy =
                retryPolicyFactory.defaultPolicy(
                        maxRetries
                );

        int attempt = 0;

        while (true) {

            attempt++;

            try {

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.INFO,
                        "Task attempt "
                                + attempt
                                + " started"
                );

                Map<String, Object> output =
                        executeAttemptWithTimeout(
                                taskExecutionId,
                                timeoutSeconds
                        );

                // The worker's TaskResultHandler atomically commits SUCCESS,
                // the output context, and downstream coordination. Plugin work
                // remains outside that short database transaction.

                return output;

            } catch (TaskLeaseLostException e) {

                /*
                 * Lease loss is NOT a normal retry.
                 *
                 * Another worker may now own this task.
                 */
                executionLogService.log(
                        taskExecutionId,
                        LogLevel.ERROR,
                        "Task lease lost; execution cannot continue safely"
                );

                throw e;

            } catch (TaskAlreadyInProgressException e) {
                throw e;

            } catch (TaskExecutionCancelledException e) {
                throw e;

            } catch (TaskTimeoutException e) {

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.ERROR,
                        "Task timed out on attempt "
                                + attempt
                );

                if (!policy.shouldRetry(attempt)) {

                    executionLogService.log(
                            taskExecutionId,
                            LogLevel.ERROR,
                            "Task failed permanently after timeout"
                    );

                    taskExecutionService.markTimeout(
                            taskExecutionId
                    );

                    throw e;
                }

                long delay =
                        retryDelay(policy, attempt, e);

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.WARN,
                        "Retrying timed out task. Retry "
                                + attempt
                                + " of "
                                + policy.getMaxRetries()
                                + " after "
                                + delay
                                + " ms"
                );

                taskExecutionService.markRetrying(
                        taskExecutionId
                );

                metrics.taskRetry(pluginType);

                Thread.sleep(delay);

            } catch (InterruptedException e) {

                /*
                 * Cancellation/interruption should NOT retry.
                 */
                Thread.currentThread()
                        .interrupt();

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.ERROR,
                        "Task interrupted during attempt "
                                + attempt
                );

                throw e;

            } catch (Exception e) {

                if (!policy.shouldRetry(attempt) || !isRetryableFailure(e)) {

                    executionLogService.log(
                            taskExecutionId,
                            LogLevel.ERROR,
                            "Task failed permanently after "
                                    + attempt
                                    + " attempt(s)"
                    );

                    taskExecutionService.markFailed(
                            taskExecutionId
                    );

                    throw e;
                }

                long delay =
                        retryDelay(policy, attempt, e);

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.WARN,
                        "Retrying task. Retry "
                                + attempt
                                + " of "
                                + policy.getMaxRetries()
                                + " after "
                                + delay
                                + " ms"
                );

                taskExecutionService.markRetrying(
                        taskExecutionId
                );

                metrics.taskRetry(pluginType);

                Thread.sleep(delay);
            }
        }
    }

    private boolean isRetryableFailure(Throwable failure) {
        // Configuration, validation, path-security, and workspace quota errors
        // are deterministic for a given task definition. Repeating external
        // operations cannot repair them and may duplicate prior side effects.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.aryan.conduit.plugin.PluginHttpFailureException httpFailure) return httpFailure.retryable();
            if (cause instanceof PluginConfigurationException
                    || cause instanceof IllegalArgumentException
                    || cause instanceof SecurityException
                    || cause instanceof com.aryan.conduit.execution.workspace.WorkspaceLimitException) {
                return false;
            }
        }
        return true;
    }

    private long retryDelay(RetryPolicy policy, int attempt, Throwable failure) {
        long backoff = policy.calculateDelay(attempt);
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof com.aryan.conduit.plugin.PluginHttpFailureException httpFailure)
                return Math.min(300_000, Math.max(backoff, httpFailure.retryAfterMillis()));
        return backoff;
    }


    private Map<String, Object> executeAttemptWithTimeout(
            Long taskExecutionId,
            Integer timeoutSeconds)
            throws InterruptedException {

        int timeout =
                timeoutSeconds == null || timeoutSeconds <= 0
                        ? 30
                        : timeoutSeconds;

        ExecutorService attemptExecutor = ATTEMPT_EXECUTOR;

        Future<Map<String, Object>> future =
                attemptExecutor.submit(
                        () -> executeTask(taskExecutionId)
                );

        try {

            return future.get(
                    timeout,
                    TimeUnit.SECONDS
            );

        } catch (TimeoutException e) {

            future.cancel(true);

            throw new TaskTimeoutException(
                    "Task "
                            + taskExecutionId
                            + " timed out after "
                    + timeout
                    + " seconds"
            );

        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;

        } catch (ExecutionException e) {

            Throwable cause =
                    e.getCause();

            if (cause instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw new RuntimeException(cause);

        } finally {

        }
    }


    private void skipRemainingStages(
            int currentStageIndex,
            List<List<Long>> stages,
            Map<Long, TaskExecution> taskExecutionMap) {

        for (int i = currentStageIndex + 1;
             i < stages.size();
             i++) {

            List<Long> stage =
                    stages.get(i);

            for (Long taskId : stage) {

                Long taskExecutionId =
                        taskExecutionMap
                                .get(taskId)
                                .getId();

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.WARN,
                        "Task Skipped"
                );

                taskExecutionService.markSkipped(
                        taskExecutionId
                );

            }
        }
    }


    private TaskNode createRuntimeTaskNode(
            TaskNode original,
            String resolvedConfiguration
    ) {
        return TaskNode.builder()
                .id(original.getId())
                .name(original.getName())
                .workflowVersion(original.getWorkflowVersion())
                .timeoutSeconds(original.getTimeoutSeconds())
                .maxRetries(original.getMaxRetries())
                .joinCondition(original.getJoinCondition())
                .pluginType(original.getPluginType())
                .configurationJson(resolvedConfiguration)
                .xPosition(original.getXPosition())
                .yPosition(original.getYPosition())
                .displayName(original.getDisplayName())
                .build();
    }

    private Map<String, Object> parsePluginConfiguration(String pluginType, String configurationJson) {
        if (configurationJson == null || configurationJson.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(configurationJson, new TypeReference<>() { });
            if (parsed == null) throw new IllegalArgumentException("configuration must be a JSON object");
            return parsed;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JSON configuration for plugin '" + pluginType + "': " + e.getMessage(), e);
        }
    }


    private Map<String, Object> pluginResultToMap(
            PluginResult result) {

        Map<String, Object> output =
                new HashMap<>();

        output.put(
                "success",
                result.isSuccess()
        );

        output.put(
                "output",
                result.getOutput()
        );

        if (result.getVariables() != null) {

            output.put(
                    "variables",
                    result.getVariables()
            );
        }

        if (result.getMetadata() != null) {

            output.put(
                    "metadata",
                    result.getMetadata()
            );
        }

        return output;
    }
}
