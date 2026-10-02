package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class KubernetesJobPlugin implements WorkflowPlugin {

    private static final String OPERATION = "KUBERNETES_JOB";

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_TIMEOUT_SECONDS = 3600;

    private static final int DEFAULT_LOG_LINES = 1000;
    private static final int MAX_LOG_LINES = 10000;

    private static final int MAX_OUTPUT_LENGTH = 32768;

    private static final Pattern DNS_LABEL =
            Pattern.compile(
                    "^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$"
            );

    private static final Pattern ENV_NAME =
            Pattern.compile(
                    "^[A-Za-z_][A-Za-z0-9_]*$"
            );

    private static final Pattern RESOURCE_QUANTITY =
            Pattern.compile(
                    "^[0-9]+(?:\\.[0-9]+)?(?:m|Mi|Gi|Ki|M|G|K)?$"
            );

    private final ObjectMapper objectMapper;

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of(
                OPERATION,
                "Kubernetes Job",
                "Runs a controlled workflow task as a Kubernetes Job",
                "1.0.0"
        );
    }

    @Override
    public void validateConfiguration(
            Map<String, Object> configuration)
            throws PluginConfigurationException {

        if (configuration == null) {
            throw new PluginConfigurationException(
                    "Configuration is required"
            );
        }

        String operation =
                requireString(
                        configuration,
                        "operation"
                );

        if (!OPERATION.equals(operation)) {
            throw new PluginConfigurationException(
                    "operation must be KUBERNETES_JOB"
            );
        }

        String image =
                requireString(
                        configuration,
                        "image"
                );

        validateImage(image);

        String namespace =
                optionalString(
                        configuration,
                        "namespace"
                );

        if (namespace != null) {
            validateDnsLabel(
                    namespace,
                    "namespace"
            );
        }

        Object command =
                configuration.get("command");

        if (command != null) {
            validateStringList(
                    command,
                    "command",
                    128
            );
        }

        Object args =
                configuration.get("args");

        if (args != null) {
            validateStringList(
                    args,
                    "args",
                    128
            );
        }

        Object environment =
                configuration.get("environment");

        if (environment != null) {
            validateEnvironment(environment);
        }

        validateResource(
                configuration,
                "cpuRequest",
                "cpuRequest"
        );

        validateResource(
                configuration,
                "cpuLimit",
                "cpuLimit"
        );

        validateResource(
                configuration,
                "memoryRequest",
                "memoryRequest"
        );

        validateResource(
                configuration,
                "memoryLimit",
                "memoryLimit"
        );

        validateTimeout(configuration);

        validateLogLines(configuration);

        validateBoolean(
                configuration,
                "cleanup"
        );

        validateBoolean(
                configuration,
                "readOnlyRootFilesystem"
        );
    }

    @Override
    public PluginResult execute(
            PluginContext context)
            throws Exception {

        Map<String, Object> configuration =
                context.configuration();

        validateConfiguration(configuration);

        String image =
                requireString(
                        configuration,
                        "image"
                );

        String namespace =
                optionalString(
                        configuration,
                        "namespace"
                );

        if (namespace == null) {
            namespace = "default";
        }

        List<String> command =
                getStringList(
                        configuration,
                        "command"
                );

        List<String> args =
                getStringList(
                        configuration,
                        "args"
                );

        Map<String, Object> environment =
                getEnvironment(configuration);

        int timeoutSeconds =
                getTimeout(configuration);

        int logLines =
                getLogLines(configuration);

        boolean cleanup =
                getBoolean(
                        configuration,
                        "cleanup",
                        true
                );

        boolean readOnlyRootFilesystem =
                getBoolean(
                        configuration,
                        "readOnlyRootFilesystem",
                        true
                );

        String cpuRequest =
                optionalString(
                        configuration,
                        "cpuRequest"
                );

        String cpuLimit =
                optionalString(
                        configuration,
                        "cpuLimit"
                );

        String memoryRequest =
                optionalString(
                        configuration,
                        "memoryRequest"
                );

        String memoryLimit =
                optionalString(
                        configuration,
                        "memoryLimit"
                );

        String jobName =
                "conduit-job-" +
                        UUID.randomUUID()
                                .toString()
                                .toLowerCase()
                                .replace(
                                        "_",
                                        "-"
                                );

        Instant startedAt =
                Instant.now();

        boolean created = false;

        try {

            String manifest =
                    buildManifest(
                            jobName,
                            namespace,
                            image,
                            command,
                            args,
                            environment,
                            timeoutSeconds,
                            cpuRequest,
                            cpuLimit,
                            memoryRequest,
                            memoryLimit,
                            readOnlyRootFilesystem
                    );

            CommandResult applyResult =
                    runKubectl(
                            List.of(
                                    "kubectl",
                                    "apply",
                                    "-f",
                                    "-"
                            ),
                            manifest,
                            30
                    );

            if (applyResult.exitCode != 0) {

                return failure(
                        "Failed to create Kubernetes Job: "
                                + applyResult.output,
                        jobName,
                        namespace,
                        image,
                        startedAt,
                        false
                );
            }

            created = true;

            boolean succeeded = false;
            boolean failed = false;
            boolean timedOut = false;

            String terminalMessage = "";

            Instant deadline =
                    Instant.now()
                            .plusSeconds(
                                    timeoutSeconds
                            );

            while (Instant.now()
                    .isBefore(deadline)) {

                CommandResult statusResult =
                        runKubectl(
                                List.of(
                                        "kubectl",
                                        "get",
                                        "job",
                                        jobName,
                                        "-n",
                                        namespace,
                                        "-o",
                                        "json"
                                ),
                                null,
                                30
                        );

                if (statusResult.exitCode != 0) {

                    terminalMessage =
                            statusResult.output;

                    Thread.sleep(1000);
                    continue;
                }

                JsonNode status =
                        objectMapper
                                .readTree(
                                        statusResult.output
                                )
                                .path("status");

                int successCount =
                        status.path(
                                "succeeded"
                        ).asInt(0);

                int failedCount =
                        status.path(
                                "failed"
                        ).asInt(0);

                if (successCount > 0) {
                    succeeded = true;
                    break;
                }

                if (failedCount > 0) {

                    failed = true;

                    terminalMessage =
                            status.path(
                                    "conditions"
                            ).toString();

                    break;
                }

                Thread.sleep(1000);
            }

            if (!succeeded && !failed) {
                timedOut = true;
            }

            String logs =
                    getJobLogs(
                            jobName,
                            namespace,
                            logLines
                    );

            long durationMs =
                    Duration.between(
                            startedAt,
                            Instant.now()
                    ).toMillis();

            boolean success =
                    succeeded && !timedOut;

            if (timedOut) {
                terminalMessage =
                        "Kubernetes Job timed out.";
            }

            if (failed &&
                    terminalMessage.isBlank()) {
                terminalMessage =
                        "Kubernetes Job failed.";
            }

            String output =
                    logs == null
                            ? terminalMessage
                            : appendOutput(
                            logs,
                            terminalMessage.isBlank()
                                    ? ""
                                    : "\n" +
                                    terminalMessage
                    );

            return PluginResult.builder()
                    .success(success)
                    .output(output)
                    .metadata(
                            Map.ofEntries(
                                    Map.entry(
                                            "operation",
                                            OPERATION
                                    ),
                                    Map.entry(
                                            "jobName",
                                            jobName
                                    ),
                                    Map.entry(
                                            "namespace",
                                            namespace
                                    ),
                                    Map.entry(
                                            "image",
                                            image
                                    ),
                                    Map.entry(
                                            "succeeded",
                                            succeeded
                                    ),
                                    Map.entry(
                                            "failed",
                                            failed
                                    ),
                                    Map.entry(
                                            "timedOut",
                                            timedOut
                                    ),
                                    Map.entry(
                                            "durationMs",
                                            durationMs
                                    ),
                                    Map.entry(
                                            "cleanup",
                                            cleanup
                                    )
                            )
                    )
                    .build();

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            return PluginResult.builder()
                    .success(false)
                    .output(
                            "Kubernetes Job execution interrupted."
                    )
                    .metadata(
                            Map.of(
                                    "operation",
                                    OPERATION,
                                    "jobName",
                                    jobName,
                                    "namespace",
                                    namespace,
                                    "interrupted",
                                    true
                            )
                    )
                    .build();

        } finally {

            if (created && cleanup) {

                deleteJob(
                        jobName,
                        namespace
                );
            }
        }
    }

    private String buildManifest(
            String jobName,
            String namespace,
            String image,
            List<String> command,
            List<String> args,
            Map<String, Object> environment,
            int timeoutSeconds,
            String cpuRequest,
            String cpuLimit,
            String memoryRequest,
            String memoryLimit,
            boolean readOnlyRootFilesystem) {

        ObjectNode root =
                objectMapper.createObjectNode();

        root.put(
                "apiVersion",
                "batch/v1"
        );

        root.put(
                "kind",
                "Job"
        );

        ObjectNode metadata =
                root.putObject("metadata");

        metadata.put(
                "name",
                jobName
        );

        metadata.put(
                "namespace",
                namespace
        );

        ObjectNode spec =
                root.putObject("spec");

        spec.put(
                "backoffLimit",
                0
        );

        spec.put(
                "activeDeadlineSeconds",
                timeoutSeconds
        );

        // Kubernetes itself can clean these up later,
        // while Conduit also performs explicit cleanup.
        spec.put(
                "ttlSecondsAfterFinished",
                300
        );

        ObjectNode template =
                spec.putObject("template");

        ObjectNode templateMetadata =
                template.putObject("metadata");

        ObjectNode labels =
                templateMetadata.putObject(
                        "labels"
                );

        labels.put(
                "app.kubernetes.io/name",
                "conduit-job"
        );

        labels.put(
                "conduit.job",
                jobName
        );

        ObjectNode podSpec =
                template.putObject("spec");

        podSpec.put(
                "restartPolicy",
                "Never"
        );

        // Workflow tasks do not need the Kubernetes API token.
        podSpec.put(
                "automountServiceAccountToken",
                false
        );

        ObjectNode securityContext =
                podSpec.putObject(
                        "securityContext"
                );

        ObjectNode seccompProfile =
                securityContext.putObject(
                        "seccompProfile"
                );

        seccompProfile.put(
                "type",
                "RuntimeDefault"
        );

        ObjectNode container =
                podSpec.putArray(
                                "containers"
                        )
                        .addObject();

        container.put(
                "name",
                "task"
        );

        container.put(
                "image",
                image
        );

        container.put(
                "imagePullPolicy",
                "IfNotPresent"
        );

        if (!command.isEmpty()) {

            ArrayNode commandNode =
                    container.putArray(
                            "command"
                    );

            command.forEach(
                    commandNode::add
            );
        }

        if (!args.isEmpty()) {

            ArrayNode argsNode =
                    container.putArray(
                            "args"
                    );

            args.forEach(
                    argsNode::add
            );
        }

        if (!environment.isEmpty()) {

            ArrayNode envNode =
                    container.putArray(
                            "env"
                    );

            for (Map.Entry<String, Object> entry :
                    environment.entrySet()) {

                ObjectNode env =
                        envNode.addObject();

                env.put(
                        "name",
                        entry.getKey()
                );

                env.put(
                        "value",
                        String.valueOf(
                                entry.getValue()
                        )
                );
            }
        }

        if (cpuRequest != null ||
                cpuLimit != null ||
                memoryRequest != null ||
                memoryLimit != null) {

            ObjectNode resources =
                    container.putObject(
                            "resources"
                    );

            if (cpuRequest != null ||
                    memoryRequest != null) {

                ObjectNode requests =
                        resources.putObject(
                                "requests"
                        );

                if (cpuRequest != null) {
                    requests.put(
                            "cpu",
                            cpuRequest
                    );
                }

                if (memoryRequest != null) {
                    requests.put(
                            "memory",
                            memoryRequest
                    );
                }
            }

            if (cpuLimit != null ||
                    memoryLimit != null) {

                ObjectNode limits =
                        resources.putObject(
                                "limits"
                        );

                if (cpuLimit != null) {
                    limits.put(
                            "cpu",
                            cpuLimit
                    );
                }

                if (memoryLimit != null) {
                    limits.put(
                            "memory",
                            memoryLimit
                    );
                }
            }
        }

        ObjectNode containerSecurity =
                container.putObject(
                        "securityContext"
                );

        containerSecurity.put(
                "allowPrivilegeEscalation",
                false
        );

        containerSecurity.put(
                "privileged",
                false
        );

        containerSecurity.put(
                "readOnlyRootFilesystem",
                readOnlyRootFilesystem
        );

        try {
            return objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root);
        } catch (Exception e) {
            throw new PluginConfigurationException(
                    "Failed to create Kubernetes Job manifest: "
                            + e.getMessage()
            );
        }
    }

    private String getJobLogs(
            String jobName,
            String namespace,
            int logLines) {

        try {

            CommandResult result =
                    runKubectl(
                            List.of(
                                    "kubectl",
                                    "logs",
                                    "-n",
                                    namespace,
                                    "-l",
                                    "conduit.job=" + jobName,
                                    "--tail=" + logLines
                            ),
                            null,
                            30
                    );

            if (result.exitCode == 0) {
                return result.output;
            }

            return "Unable to retrieve Kubernetes Job logs: "
                    + result.output;

        } catch (Exception e) {

            return "Unable to retrieve Kubernetes Job logs: "
                    + e.getMessage();
        }
    }

    private void deleteJob(
            String jobName,
            String namespace) {

        try {

            runKubectl(
                    List.of(
                            "kubectl",
                            "delete",
                            "job",
                            jobName,
                            "-n",
                            namespace,
                            "--ignore-not-found=true",
                            "--wait=false"
                    ),
                    null,
                    30
            );

        } catch (Exception e) {

            log.warn(
                    "Failed to cleanup Kubernetes Job {}: {}",
                    jobName,
                    e.getMessage()
            );
        }
    }

    private CommandResult runKubectl(
            List<String> command,
            String stdin,
            int timeoutSeconds)
            throws Exception {

        ProcessBuilder builder =
                new ProcessBuilder(command);

        builder.redirectErrorStream(true);

        Process process =
                builder.start();

        if (stdin != null) {

            process.getOutputStream()
                    .write(
                            stdin.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            process.getOutputStream()
                    .close();
        }

        String output;

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     process.getInputStream(),
                                     StandardCharsets.UTF_8
                             )
                     )) {

            StringBuilder buffer =
                    new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {

                if (buffer.length()
                        + line.length()
                        + 1
                        <= MAX_OUTPUT_LENGTH) {

                    buffer.append(line)
                            .append('\n');
                }
            }

            output = buffer.toString();
        }

        boolean completed =
                process.waitFor(
                        timeoutSeconds,
                        TimeUnit.SECONDS
                );

        if (!completed) {

            process.destroy();

            if (!process.waitFor(
                    5,
                    TimeUnit.SECONDS
            )) {
                process.destroyForcibly();
            }

            return new CommandResult(
                    -1,
                    appendOutput(
                            output,
                            "\nkubectl command timed out."
                    )
            );
        }

        return new CommandResult(
                process.exitValue(),
                output
        );
    }

    private PluginResult failure(
            String message,
            String jobName,
            String namespace,
            String image,
            Instant startedAt,
            boolean timedOut) {

        return PluginResult.builder()
                .success(false)
                .output(message)
                .metadata(
                        Map.of(
                                "operation",
                                OPERATION,
                                "jobName",
                                jobName,
                                "namespace",
                                namespace,
                                "image",
                                image,
                                "timedOut",
                                timedOut,
                                "durationMs",
                                Duration.between(
                                        startedAt,
                                        Instant.now()
                                ).toMillis()
                        )
                )
                .build();
    }

    private void validateImage(
            String image) {

        if (image.length() > 512) {
            throw new PluginConfigurationException(
                    "image reference is too long"
            );
        }

        rejectControlCharacters(
                image,
                "image"
        );
    }

    private void validateDnsLabel(
            String value,
            String field) {

        if (value.length() > 63 ||
                !DNS_LABEL.matcher(value).matches()) {

            throw new PluginConfigurationException(
                    field +
                            " must be a valid Kubernetes DNS label"
            );
        }
    }

    private void validateStringList(
            Object value,
            String field,
            int maxSize) {

        if (!(value instanceof List<?> list)) {
            throw new PluginConfigurationException(
                    field + " must be an array"
            );
        }

        if (list.size() > maxSize) {
            throw new PluginConfigurationException(
                    field +
                            " cannot contain more than " +
                            maxSize +
                            " entries"
            );
        }

        for (Object item : list) {

            if (!(item instanceof String string) ||
                    string.isBlank()) {

                throw new PluginConfigurationException(
                        field +
                                " entries must be non-blank strings"
                );
            }

            rejectControlCharacters(
                    string,
                    field
            );
        }
    }

    private void validateEnvironment(
            Object value) {

        if (!(value instanceof Map<?, ?> map)) {
            throw new PluginConfigurationException(
                    "environment must be an object"
            );
        }

        if (map.size() > 100) {
            throw new PluginConfigurationException(
                    "environment cannot contain more than 100 variables"
            );
        }

        for (Map.Entry<?, ?> entry :
                map.entrySet()) {

            if (!(entry.getKey()
                    instanceof String key) ||
                    !ENV_NAME.matcher(key).matches()) {

                throw new PluginConfigurationException(
                        "Invalid environment variable name"
                );
            }

            if (entry.getValue() == null) {
                throw new PluginConfigurationException(
                        "Environment variable values cannot be null"
                );
            }

            rejectControlCharacters(
                    String.valueOf(
                            entry.getValue()
                    ),
                    "environment"
            );
        }
    }

    private void validateResource(
            Map<String, Object> configuration,
            String key,
            String field) {

        String value =
                optionalString(
                        configuration,
                        key
                );

        if (value == null) {
            return;
        }

        if (!RESOURCE_QUANTITY
                .matcher(value)
                .matches()) {

            throw new PluginConfigurationException(
                    field +
                            " contains an invalid Kubernetes resource quantity"
            );
        }
    }

    private void validateTimeout(
            Map<String, Object> configuration) {

        Object value =
                configuration.get(
                        "timeoutSeconds"
                );

        if (value == null) {
            return;
        }

        if (!(value instanceof Number number)) {
            throw new PluginConfigurationException(
                    "timeoutSeconds must be numeric"
            );
        }

        int timeout =
                number.intValue();

        if (timeout < 1 ||
                timeout > MAX_TIMEOUT_SECONDS) {

            throw new PluginConfigurationException(
                    "timeoutSeconds must be between 1 and "
                            + MAX_TIMEOUT_SECONDS
            );
        }
    }

    private void validateLogLines(
            Map<String, Object> configuration) {

        Object value =
                configuration.get(
                        "logLines"
                );

        if (value == null) {
            return;
        }

        if (!(value instanceof Number number)) {
            throw new PluginConfigurationException(
                    "logLines must be numeric"
            );
        }

        int lines =
                number.intValue();

        if (lines < 1 ||
                lines > MAX_LOG_LINES) {

            throw new PluginConfigurationException(
                    "logLines must be between 1 and "
                            + MAX_LOG_LINES
            );
        }
    }

    private void validateBoolean(
            Map<String, Object> configuration,
            String key) {

        Object value =
                configuration.get(key);

        if (value != null &&
                !(value instanceof Boolean)) {

            throw new PluginConfigurationException(
                    key + " must be boolean"
            );
        }
    }

    private String requireString(
            Map<String, Object> configuration,
            String key) {

        Object value =
                configuration.get(key);

        if (!(value instanceof String string) ||
                string.isBlank()) {

            throw new PluginConfigurationException(
                    key + " is required"
            );
        }

        rejectControlCharacters(
                string,
                key
        );

        return string.trim();
    }

    private String optionalString(
            Map<String, Object> configuration,
            String key) {

        Object value =
                configuration.get(key);

        if (value == null) {
            return null;
        }

        if (!(value instanceof String string)) {
            throw new PluginConfigurationException(
                    key + " must be a string"
            );
        }

        if (string.isBlank()) {
            return null;
        }

        rejectControlCharacters(
                string,
                key
        );

        return string.trim();
    }

    private List<String> getStringList(
            Map<String, Object> configuration,
            String key) {

        Object value =
                configuration.get(key);

        if (value == null) {
            return List.of();
        }

        List<?> list =
                (List<?>) value;

        List<String> result =
                new ArrayList<>();

        for (Object item : list) {
            result.add(
                    String.valueOf(item)
            );
        }

        return result;
    }

    private Map<String, Object> getEnvironment(
            Map<String, Object> configuration) {

        Object value =
                configuration.get(
                        "environment"
                );

        if (value == null) {
            return Map.of();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> environment =
                (Map<String, Object>) value;

        return environment;
    }

    private int getTimeout(
            Map<String, Object> configuration) {

        Object value =
                configuration.get(
                        "timeoutSeconds"
                );

        return value == null
                ? DEFAULT_TIMEOUT_SECONDS
                : ((Number) value).intValue();
    }

    private int getLogLines(
            Map<String, Object> configuration) {

        Object value =
                configuration.get(
                        "logLines"
                );

        return value == null
                ? DEFAULT_LOG_LINES
                : ((Number) value).intValue();
    }

    private boolean getBoolean(
            Map<String, Object> configuration,
            String key,
            boolean defaultValue) {

        Object value =
                configuration.get(key);

        return value == null
                ? defaultValue
                : (Boolean) value;
    }

    private void rejectControlCharacters(
            String value,
            String field) {

        for (char character :
                value.toCharArray()) {

            if (Character.isISOControl(character) &&
                    character != '\t') {

                throw new PluginConfigurationException(
                        field +
                                " contains control characters"
                );
            }
        }
    }

    private String appendOutput(
            String output,
            String suffix) {

        String combined =
                output + suffix;

        if (combined.length()
                <= MAX_OUTPUT_LENGTH) {

            return combined;
        }

        return combined.substring(
                0,
                MAX_OUTPUT_LENGTH
        );
    }

    private record CommandResult(
            int exitCode,
            String output) {
    }
}