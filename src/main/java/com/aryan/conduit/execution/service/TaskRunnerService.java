
package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.*;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.retry.RetryPolicy;
import com.aryan.conduit.execution.retry.RetryPolicyFactory;
import com.aryan.conduit.execution.retry.TaskLeaseHeartbeat;
import com.aryan.conduit.execution.retry.TaskLeaseLostException;
import com.aryan.conduit.execution.retry.TaskAlreadyInProgressException;
import com.aryan.conduit.execution.retry.TaskTimeoutException;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class TaskRunnerService {

    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskExecutionService taskExecutionService;
    private final ExecutionLogService executionLogService;
    private final TaskOutputService taskOutputService;
    private final PluginManager pluginManager;
    private final RetryPolicyFactory retryPolicyFactory;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final ConfigurationResolver configurationResolver;
    private final ConduitMetrics metrics;
    private final ExpressionContextService expressionContextService;


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
        taskExecutionService.markRunning(taskExecutionId);

        try {

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

            TaskNode runtimeTaskNode =
                    createRuntimeTaskNode(
                            taskExecution.getTaskNode(),
                            resolvedConfiguration
                    );

            Map<String, Object> pluginConfiguration = parsePluginConfiguration(
                    pluginType, resolvedConfiguration);
            try {
                plugin.validateConfiguration(pluginConfiguration);
            } catch (PluginConfigurationException e) {
                throw new IllegalArgumentException("Invalid configuration for plugin '" + pluginType + "': " + e.getMessage(), e);
            }

            Map<String, Object> variables = expressionContextService.buildContext(
                    taskExecution.getWorkflowExecution().getId());
            PluginContext pluginContext = new PluginContext(
                    runtimeTaskNode.getName(), runtimeTaskNode.getTimeoutSeconds(), pluginConfiguration, variables);
            PluginResult result = plugin.execute(pluginContext);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Plugin execution was interrupted");
            }
            if (result == null) {
                throw new IllegalStateException("Plugin '" + pluginType + "' returned no result");
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

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.ERROR,
                        "Plugin execution failed: "
                                + result.getOutput()
                );

                /*
                 * Only release if we still own the lease.
                 *
                 * The token protects us from accidentally
                 * releasing another worker's lease.
                 */
                if (!heartbeat.isLeaseLost()) {

                    idempotencyService.release(
                            idempotencyKey,
                            token
                    );
                }

                throw new RuntimeException(
                        result.getOutput()
                );
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

            taskExecutionService.markSuccess(taskExecutionId);

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
            executionLogService.log(
                    taskExecutionId,
                    LogLevel.ERROR,
                    "Task execution stopped because lease was lost"
            );

            throw e;

        } catch (Exception e) {

            /*
             * If lease was lost, DO NOT release it.
             *
             * The lease token additionally protects against
             * releasing another worker's lease.
             */
            if (!heartbeat.isLeaseLost()) {

                idempotencyService.release(
                        idempotencyKey,
                        token
                );
            }

            executionLogService.log(
                    taskExecutionId,
                    LogLevel.ERROR,
                    "Plugin execution failed: "
                            + e.getMessage()
            );

            if (e instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Plugin execution failed: " + e.getMessage(), e);

        } finally {

            /*
             * Always stop the heartbeat when this attempt
             * finishes.
             */
            heartbeat.stop();
        }
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

                TaskExecution taskExecution =
                        taskExecutionRepository
                                .findById(taskExecutionId)
                                .orElseThrow();

                Long workflowExecutionId =
                        taskExecution
                                .getWorkflowExecution()
                                .getId();

                taskOutputService.storeOutput(
                        workflowExecutionId,
                        taskExecution
                                .getTaskNode()
                                .getId(),
                        output
                );

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.INFO,
                        "Task completed successfully on attempt "
                                + attempt
                );

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
                        policy.calculateDelay(attempt);

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

                if (!policy.shouldRetry(attempt)) {

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
                        policy.calculateDelay(attempt);

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


    private Map<String, Object> executeAttemptWithTimeout(
            Long taskExecutionId,
            Integer timeoutSeconds)
            throws InterruptedException {

        int timeout =
                timeoutSeconds == null || timeoutSeconds <= 0
                        ? 30
                        : timeoutSeconds;

        ExecutorService attemptExecutor =
                Executors.newSingleThreadExecutor();

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

        } catch (ExecutionException e) {

            Throwable cause =
                    e.getCause();

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw new RuntimeException(cause);

        } finally {

            attemptExecutor.shutdownNow();
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
