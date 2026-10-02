package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
@Slf4j
public class DockerRunPlugin implements WorkflowPlugin {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_TIMEOUT_SECONDS = 3600;
    private static final int MAX_OUTPUT_LENGTH = 32768;

    private static final double DEFAULT_CPUS = 1.0;
    private static final long DEFAULT_MEMORY_MB = 256;
    private static final long DEFAULT_PIDS_LIMIT = 256;

    private static final Pattern IMAGE_PATTERN =
            Pattern.compile(
                    "^[a-zA-Z0-9][a-zA-Z0-9._:/@-]*$"
            );

    private static final Pattern ENV_NAME_PATTERN =
            Pattern.compile(
                    "^[A-Za-z_][A-Za-z0-9_]*$"
            );

    private static final Pattern WORKING_DIRECTORY_PATTERN =
            Pattern.compile(
                    "^/[a-zA-Z0-9._/\\-]*$"
            );

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of(
                "DOCKER_RUN",
                "Docker Run",
                "Runs a controlled Docker container for a workflow task",
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

        String operation = requireString(
                configuration,
                "operation"
        );

        if (!"DOCKER_RUN".equals(operation)) {
            throw new PluginConfigurationException(
                    "operation must be DOCKER_RUN"
            );
        }

        String image = requireString(
                configuration,
                "image"
        );

        validateImage(image);

        Object command = configuration.get("command");

        if (command != null) {
            validateCommand(command);
        }

        Object environment =
                configuration.get("environment");

        if (environment != null) {
            validateEnvironment(environment);
        }

        String workingDirectory =
                optionalString(
                        configuration,
                        "workingDirectory"
                );

        if (workingDirectory != null) {
            validateWorkingDirectory(
                    workingDirectory
            );
        }

        validatePositiveDouble(
                configuration,
                "cpus",
                DEFAULT_CPUS,
                0.1,
                64.0
        );

        validatePositiveLong(
                configuration,
                "memoryMb",
                DEFAULT_MEMORY_MB,
                16,
                65536
        );

        validatePositiveLong(
                configuration,
                "pidsLimit",
                DEFAULT_PIDS_LIMIT,
                16,
                100000
        );

        validateTimeout(configuration);

        validateBoolean(
                configuration,
                "readOnlyRootFilesystem"
        );

        validateBoolean(
                configuration,
                "networkDisabled"
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
                requireString(configuration, "image");

        List<String> command =
                getCommand(configuration);

        Map<String, Object> environment =
                getEnvironment(configuration);

        String workingDirectory =
                optionalString(
                        configuration,
                        "workingDirectory"
                );

        double cpus = getDouble(
                configuration,
                "cpus",
                DEFAULT_CPUS
        );

        long memoryMb = getLong(
                configuration,
                "memoryMb",
                DEFAULT_MEMORY_MB
        );

        long pidsLimit = getLong(
                configuration,
                "pidsLimit",
                DEFAULT_PIDS_LIMIT
        );

        int timeoutSeconds =
                getTimeout(configuration);

        boolean readOnlyRootFilesystem =
                getBoolean(
                        configuration,
                        "readOnlyRootFilesystem",
                        true
                );

        boolean networkDisabled =
                getBoolean(
                        configuration,
                        "networkDisabled",
                        false
                );

        String containerName =
                "conduit-task-" +
                        UUID.randomUUID();

        List<String> dockerCommand =
                new ArrayList<>();

        dockerCommand.add("docker");
        dockerCommand.add("run");

        // Always clean up the container.
        dockerCommand.add("--rm");

        dockerCommand.add("--name");
        dockerCommand.add(containerName);

        // Resource limits.
        dockerCommand.add("--cpus");
        dockerCommand.add(String.valueOf(cpus));

        dockerCommand.add("--memory");
        dockerCommand.add(memoryMb + "m");

        dockerCommand.add("--pids-limit");
        dockerCommand.add(String.valueOf(pidsLimit));

        // Security hardening.
        dockerCommand.add("--security-opt");
        dockerCommand.add("no-new-privileges");

        if (readOnlyRootFilesystem) {
            dockerCommand.add("--read-only");
        }

        if (networkDisabled) {
            dockerCommand.add("--network");
            dockerCommand.add("none");
        }

        if (workingDirectory != null) {
            dockerCommand.add("--workdir");
            dockerCommand.add(workingDirectory);
        }

        // Environment variables.
        for (Map.Entry<String, Object> entry :
                environment.entrySet()) {

            dockerCommand.add("--env");
            dockerCommand.add(
                    entry.getKey() +
                            "=" +
                            String.valueOf(
                                    entry.getValue()
                            )
            );
        }

        // Image.
        dockerCommand.add(image);

        // Optional command.
        dockerCommand.addAll(command);

        Instant startedAt = Instant.now();

        Process process = null;

        try {
            ProcessBuilder processBuilder =
                    new ProcessBuilder(
                            dockerCommand
                    );

            // Capture Docker stdout + stderr together.
            processBuilder.redirectErrorStream(true);

            process =
                    processBuilder.start();

            Process finalProcess = process;
            CompletableFuture<String> outputFuture =
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return readOutputWithLimit(finalProcess);
                        } catch (Exception e) {
                            return "Failed to capture Docker output: "
                                    + e.getMessage();
                        }
                    });

            boolean completed =
                    process.waitFor(
                            timeoutSeconds,
                            TimeUnit.SECONDS
                    );

            String output;

            try {
                output = outputFuture.get(
                        5,
                        TimeUnit.SECONDS
                );
            } catch (Exception e) {
                output = "Docker output capture failed: "
                        + e.getMessage();
            }

            if (!completed) {

                log.warn(
                        "Docker container timed out: {}",
                        containerName
                );

                destroyProcess(process);

                long durationMs =
                        Duration.between(
                                startedAt,
                                Instant.now()
                        ).toMillis();

                return PluginResult.builder()
                        .success(false)
                        .output(
                                appendOutput(
                                        output,
                                        "\nDocker execution timed out."
                                )
                        )
                        .metadata(
                                Map.of(
                                        "operation",
                                        "DOCKER_RUN",
                                        "image",
                                        image,
                                        "containerName",
                                        containerName,
                                        "timedOut",
                                        true,
                                        "exitCode",
                                        -1,
                                        "durationMs",
                                        durationMs
                                )
                        )
                        .build();
            }

            int exitCode =
                    process.exitValue();

            long durationMs =
                    Duration.between(
                            startedAt,
                            Instant.now()
                    ).toMillis();

            boolean success =
                    exitCode == 0;

            return PluginResult.builder()
                    .success(success)
                    .output(output)
                    .metadata(
                            Map.ofEntries(
                                    Map.entry("operation", "DOCKER_RUN"),
                                    Map.entry("image", image),
                                    Map.entry("containerName", containerName),
                                    Map.entry("exitCode", exitCode),
                                    Map.entry("timedOut", false),
                                    Map.entry("durationMs", durationMs),
                                    Map.entry("cpus", cpus),
                                    Map.entry("memoryMb", memoryMb),
                                    Map.entry("pidsLimit", pidsLimit),
                                    Map.entry("networkDisabled", networkDisabled),
                                    Map.entry(
                                            "readOnlyRootFilesystem",
                                            readOnlyRootFilesystem
                                    )
                            )
                    )
                    .build();

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            if (process != null) {
                destroyProcess(process);
            }

            return PluginResult.builder()
                    .success(false)
                    .output(
                            "Docker execution interrupted."
                    )
                    .metadata(
                            Map.of(
                                    "operation",
                                    "DOCKER_RUN",
                                    "image",
                                    image,
                                    "containerName",
                                    containerName,
                                    "interrupted",
                                    true
                            )
                    )
                    .build();

        } catch (Exception e) {

            log.error(
                    "Docker execution failed",
                    e
            );

            if (process != null) {
                destroyProcess(process);
            }

            return PluginResult.builder()
                    .success(false)
                    .output(
                            "Docker execution failed: " +
                                    e.getMessage()
                    )
                    .metadata(
                            Map.of(
                                    "operation",
                                    "DOCKER_RUN",
                                    "image",
                                    image,
                                    "containerName",
                                    containerName,
                                    "error",
                                    e.getClass()
                                            .getSimpleName()
                            )
                    )
                    .build();
        }
    }

    private String readOutputWithLimit(
            Process process)
            throws Exception {

        StringBuilder output =
                new StringBuilder();

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     process.getInputStream(),
                                     StandardCharsets.UTF_8
                             )
                     )) {

            String line;

            while ((line = reader.readLine()) != null) {

                if (output.length()
                        + line.length()
                        + 1
                        <= MAX_OUTPUT_LENGTH) {

                    output.append(line)
                            .append('\n');
                } else {

                    output.append(
                            "\n[output truncated]"
                    );

                    break;
                }
            }
        }

        return output.toString();
    }

    private void destroyProcess(
            Process process) {

        process.destroy();

        try {
            if (!process.waitFor(
                    5,
                    TimeUnit.SECONDS
            )) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private List<String> getCommand(
            Map<String, Object> configuration) {

        Object value =
                configuration.get("command");

        if (value == null) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Object> values =
                (List<Object>) value;

        List<String> result =
                new ArrayList<>();

        for (Object item : values) {
            result.add(String.valueOf(item));
        }

        return result;
    }

    private Map<String, Object> getEnvironment(
            Map<String, Object> configuration) {

        Object value =
                configuration.get("environment");

        if (value == null) {
            return Map.of();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> environment =
                (Map<String, Object>) value;

        return environment;
    }

    private void validateCommand(
            Object command) {

        if (!(command instanceof List<?> list)) {
            throw new PluginConfigurationException(
                    "command must be an array"
            );
        }

        if (list.size() > 128) {
            throw new PluginConfigurationException(
                    "command cannot contain more than 128 arguments"
            );
        }

        for (Object argument : list) {

            if (argument == null) {
                throw new PluginConfigurationException(
                        "command arguments cannot be null"
                );
            }

            String value =
                    String.valueOf(argument);

            rejectControlCharacters(
                    value,
                    "command argument"
            );
        }
    }

    private void validateEnvironment(
            Object environment) {

        if (!(environment instanceof Map<?, ?> map)) {
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

            if (!(entry.getKey() instanceof String key)) {
                throw new PluginConfigurationException(
                        "environment variable names must be strings"
                );
            }

            if (!ENV_NAME_PATTERN.matcher(key).matches()) {
                throw new PluginConfigurationException(
                        "Invalid environment variable name: " +
                                key
                );
            }

            if (entry.getValue() == null) {
                throw new PluginConfigurationException(
                        "Environment variable values cannot be null"
                );
            }

            rejectControlCharacters(
                    String.valueOf(entry.getValue()),
                    "environment variable"
            );
        }
    }

    private void validateImage(
            String image) {

        if (image.length() > 512) {
            throw new PluginConfigurationException(
                    "image reference is too long"
            );
        }

        if (!IMAGE_PATTERN.matcher(image).matches()) {
            throw new PluginConfigurationException(
                    "Invalid Docker image reference"
            );
        }

        rejectControlCharacters(
                image,
                "image"
        );
    }

    private void validateWorkingDirectory(
            String workingDirectory) {

        if (!WORKING_DIRECTORY_PATTERN
                .matcher(workingDirectory)
                .matches()) {

            throw new PluginConfigurationException(
                    "workingDirectory must be an absolute Unix-style path"
            );
        }

        if (workingDirectory.contains("..")) {
            throw new PluginConfigurationException(
                    "workingDirectory cannot contain '..'"
            );
        }
    }

    private void validateTimeout(
            Map<String, Object> configuration) {

        Object value =
                configuration.get("timeoutSeconds");

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
                    "timeoutSeconds must be between 1 and " +
                            MAX_TIMEOUT_SECONDS
            );
        }
    }

    private void validatePositiveDouble(
            Map<String, Object> configuration,
            String key,
            double defaultValue,
            double min,
            double max) {

        Object value =
                configuration.get(key);

        if (value == null) {
            return;
        }

        if (!(value instanceof Number number)) {
            throw new PluginConfigurationException(
                    key + " must be numeric"
            );
        }

        double numberValue =
                number.doubleValue();

        if (!Double.isFinite(numberValue) ||
                numberValue < min ||
                numberValue > max) {

            throw new PluginConfigurationException(
                    key +
                            " must be between " +
                            min +
                            " and " +
                            max
            );
        }
    }

    private void validatePositiveLong(
            Map<String, Object> configuration,
            String key,
            long defaultValue,
            long min,
            long max) {

        Object value =
                configuration.get(key);

        if (value == null) {
            return;
        }

        if (!(value instanceof Number number)) {
            throw new PluginConfigurationException(
                    key + " must be numeric"
            );
        }

        long numberValue =
                number.longValue();

        if (numberValue < min ||
                numberValue > max) {

            throw new PluginConfigurationException(
                    key +
                            " must be between " +
                            min +
                            " and " +
                            max
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

    private int getTimeout(
            Map<String, Object> configuration) {

        Object value =
                configuration.get(
                        "timeoutSeconds"
                );

        if (value == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }

        return ((Number) value).intValue();
    }

    private double getDouble(
            Map<String, Object> configuration,
            String key,
            double defaultValue) {

        Object value =
                configuration.get(key);

        return value == null
                ? defaultValue
                : ((Number) value).doubleValue();
    }

    private long getLong(
            Map<String, Object> configuration,
            String key,
            long defaultValue) {

        Object value =
                configuration.get(key);

        return value == null
                ? defaultValue
                : ((Number) value).longValue();
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

        for (char character : value.toCharArray()) {

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
}