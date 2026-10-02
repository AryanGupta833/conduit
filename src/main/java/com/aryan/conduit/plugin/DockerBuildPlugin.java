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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
public class DockerBuildPlugin implements WorkflowPlugin {

    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int MAX_TIMEOUT_SECONDS = 3600;

    private static final int MAX_OUTPUT_LENGTH = 32_768;

    private static final String OPERATION =
            "DOCKER_BUILD";

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of(
                OPERATION,
                "Docker Build",
                "Build a Docker image from a workspace context",
                "1.0.0"
        );
    }

    @Override
    public void validateConfiguration(
            Map<String, Object> configuration)
            throws PluginConfigurationException {

        if (configuration == null) {
            throw new PluginConfigurationException(
                    "Docker build configuration is required"
            );
        }

        String operation =
                requireString(
                        configuration,
                        "operation"
                );

        if (!OPERATION.equalsIgnoreCase(operation)) {
            throw new PluginConfigurationException(
                    "Unsupported Docker operation: "
                            + operation
            );
        }

        String image =
                requireString(
                        configuration,
                        "image"
                );

        validateImageReference(image);

        String workspaceRoot =
                requireString(
                        configuration,
                        "workspaceRoot"
                );

        validateWorkspaceRoot(workspaceRoot);

        String contextPath =
                requireString(
                        configuration,
                        "contextPath"
                );

        validateRelativePath(
                contextPath,
                "contextPath"
        );

        String dockerfile =
                optionalString(
                        configuration,
                        "dockerfile"
                );

        if (dockerfile != null) {
            validateDockerfilePath(
                    dockerfile
            );
        }

        String target =
                optionalString(
                        configuration,
                        "target"
                );

        if (target != null) {
            validateIdentifier(
                    target,
                    "target"
            );
        }

        String platform =
                optionalString(
                        configuration,
                        "platform"
                );

        if (platform != null) {
            validatePlatform(platform);
        }

        validateBuildArgs(configuration);

        validateBoolean(
                configuration,
                "noCache"
        );

        validateBoolean(
                configuration,
                "pull"
        );

        validateTimeout(configuration);
    }

    @Override
    public PluginResult execute(
            PluginContext context)
            throws Exception {

        Map<String, Object> configuration =
                context.configuration();

        validateConfiguration(configuration);

        Path workspaceRoot =
                resolveWorkspaceRoot(
                        configuration
                );

        Path contextDirectory =
                resolveRelativePath(
                        workspaceRoot,
                        requireString(
                                configuration,
                                "contextPath"
                        ),
                        "contextPath"
                );

        if (!Files.exists(contextDirectory)) {
            throw new PluginConfigurationException(
                    "Docker build context does not exist: "
                            + contextDirectory
            );
        }

        if (!Files.isDirectory(contextDirectory)) {
            throw new PluginConfigurationException(
                    "Docker build context must be a directory: "
                            + contextDirectory
            );
        }

        String dockerfile =
                optionalString(
                        configuration,
                        "dockerfile"
                );

        Path dockerfilePath =
                resolveDockerfile(
                        contextDirectory,
                        dockerfile
                );

        if (!Files.exists(dockerfilePath)) {
            throw new PluginConfigurationException(
                    "Dockerfile does not exist: "
                            + dockerfilePath
            );
        }

        if (!Files.isRegularFile(dockerfilePath)) {
            throw new PluginConfigurationException(
                    "Dockerfile must be a regular file: "
                            + dockerfilePath
            );
        }

        String image =
                requireString(
                        configuration,
                        "image"
                );

        int timeoutSeconds =
                getTimeout(configuration);

        List<String> command =
                buildCommand(
                        configuration,
                        contextDirectory,
                        dockerfilePath,
                        image
                );

        Instant start =
                Instant.now();

        ProcessResult processResult;

        try {
            processResult =
                    runDocker(
                            command,
                            timeoutSeconds
                    );
        } catch (TimeoutException e) {

            return failureResult(
                    start,
                    true,
                    null,
                    "Docker build timed out"
            );
        }

        Map<String, Object> metadata =
                baseMetadata(
                        start,
                        processResult
                );

        metadata.put(
                "image",
                image
        );

        metadata.put(
                "contextPath",
                contextDirectory.toString()
        );

        metadata.put(
                "dockerfile",
                dockerfilePath.toString()
        );

        return PluginResult.builder()
                .success(
                        processResult.exitCode() == 0
                )
                .output(
                        processResult.output()
                )
                .metadata(metadata)
                .build();
    }

    // -------------------------------------------------------------------------
    // Docker command
    // -------------------------------------------------------------------------

    private List<String> buildCommand(
            Map<String, Object> configuration,
            Path contextDirectory,
            Path dockerfile,
            String image) {

        List<String> command =
                new ArrayList<>();

        command.add("docker");
        command.add("build");

        command.add("--tag");
        command.add(image);

        command.add("--file");
        command.add(dockerfile.toString());

        String platform =
                optionalString(
                        configuration,
                        "platform"
                );

        if (platform != null) {
            command.add("--platform");
            command.add(platform);
        }

        String target =
                optionalString(
                        configuration,
                        "target"
                );

        if (target != null) {
            command.add("--target");
            command.add(target);
        }

        boolean noCache =
                getBoolean(
                        configuration,
                        "noCache",
                        false
                );

        if (noCache) {
            command.add("--no-cache");
        }

        boolean pull =
                getBoolean(
                        configuration,
                        "pull",
                        false
                );

        if (pull) {
            command.add("--pull");
        }

        Map<String, Object> buildArgs =
                getBuildArgs(configuration);

        for (Map.Entry<String, Object> entry :
                buildArgs.entrySet()) {

            command.add("--build-arg");
            command.add(
                    entry.getKey()
                            + "="
                            + String.valueOf(
                            entry.getValue()
                    )
            );
        }

        command.add(
                contextDirectory.toString()
        );

        return command;
    }

    // -------------------------------------------------------------------------
    // Process execution
    // -------------------------------------------------------------------------

    private ProcessResult runDocker(
            List<String> command,
            int timeoutSeconds)
            throws Exception {

        ProcessBuilder processBuilder =
                new ProcessBuilder(command);

        Process process =
                processBuilder.start();

        Instant start =
                Instant.now();

        StringBuilder output =
                new StringBuilder();

        Thread outputReader =
                Thread.startVirtualThread(() -> {

                    try (BufferedReader reader =
                                 new BufferedReader(
                                         new InputStreamReader(
                                                 process.getInputStream(),
                                                 StandardCharsets.UTF_8
                                         )
                                 )) {

                        String line;

                        while ((line =
                                reader.readLine()) != null) {

                            synchronized (output) {

                                if (output.length()
                                        >= MAX_OUTPUT_LENGTH) {
                                    continue;
                                }

                                int remaining =
                                        MAX_OUTPUT_LENGTH
                                                - output.length();

                                String text =
                                        line
                                                + System.lineSeparator();

                                if (text.length()
                                        <= remaining) {

                                    output.append(text);

                                } else {

                                    output.append(
                                            text,
                                            0,
                                            remaining
                                    );
                                }
                            }
                        }

                    } catch (Exception e) {

                        log.debug(
                                "Docker output reader stopped",
                                e
                        );
                    }
                });

        boolean completed =
                process.waitFor(
                        timeoutSeconds,
                        TimeUnit.SECONDS
                );

        if (!completed) {

            process.destroy();

            if (!process.waitFor(
                    2,
                    TimeUnit.SECONDS
            )) {
                process.destroyForcibly();
            }

            throw new TimeoutException(
                    "Docker build timed out"
            );
        }

        outputReader.join(
                2_000
        );

        return new ProcessResult(
                process.exitValue(),
                output.toString(),
                Duration.between(
                        start,
                        Instant.now()
                ).toMillis()
        );
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private void validateWorkspaceRoot(
            String workspaceRoot) {

        try {
            Path path =
                    Path.of(workspaceRoot);

            if (!path.isAbsolute()) {
                throw new PluginConfigurationException(
                        "workspaceRoot must be absolute"
                );
            }

        } catch (PluginConfigurationException e) {
            throw e;

        } catch (Exception e) {
            throw new PluginConfigurationException(
                    "Invalid workspaceRoot",
                    e
            );
        }
    }

    private void validateRelativePath(
            String value,
            String field) {

        if (value == null || value.isBlank()) {
            throw new PluginConfigurationException(
                    field + " is required"
            );
        }

        Path path;

        try {
            path =
                    Path.of(value);

        } catch (Exception e) {
            throw new PluginConfigurationException(
                    "Invalid " + field,
                    e
            );
        }

        if (path.isAbsolute()) {
            throw new PluginConfigurationException(
                    field + " must be relative"
            );
        }

        for (Path segment : path) {

            if ("..".equals(
                    segment.toString()
            )) {
                throw new PluginConfigurationException(
                        field
                                + " cannot contain '..' traversal segments"
                );
            }
        }

        validateNoControlCharacters(
                value,
                field
        );
    }

    private void validateDockerfilePath(
            String dockerfile) {

        if (dockerfile.isBlank()) {
            throw new PluginConfigurationException(
                    "dockerfile cannot be blank"
            );
        }

        Path path;

        try {
            path =
                    Path.of(dockerfile);

        } catch (Exception e) {
            throw new PluginConfigurationException(
                    "Invalid dockerfile path",
                    e
            );
        }

        if (path.isAbsolute()) {
            throw new PluginConfigurationException(
                    "dockerfile must be relative to contextPath"
            );
        }

        for (Path segment : path) {

            if ("..".equals(
                    segment.toString()
            )) {
                throw new PluginConfigurationException(
                        "dockerfile cannot contain '..' traversal segments"
                );
            }
        }

        validateNoControlCharacters(
                dockerfile,
                "dockerfile"
        );
    }

    private void validateImageReference(
            String image) {

        if (image.isBlank()) {
            throw new PluginConfigurationException(
                    "image is required"
            );
        }

        validateNoControlCharacters(
                image,
                "image"
        );

        if (image.length() > 255) {
            throw new PluginConfigurationException(
                    "image reference is too long"
            );
        }

        if (image.startsWith("-")) {
            throw new PluginConfigurationException(
                    "image cannot start with '-'"
            );
        }

        /*
         * Keep the plugin deliberately conservative.
         *
         * Valid examples:
         *
         *   nginx
         *   nginx:latest
         *   myrepo/app:v1
         *   registry.example.com/team/app:1.0
         *   registry.example.com:5000/team/app:1.0
         */
        if (!image.matches(
                "^[a-zA-Z0-9][a-zA-Z0-9._:/@-]*$"
        )) {
            throw new PluginConfigurationException(
                    "Invalid Docker image reference: "
                            + image
            );
        }
    }

    private void validateIdentifier(
            String value,
            String field) {

        if (value.isBlank()) {
            throw new PluginConfigurationException(
                    field + " cannot be blank"
            );
        }

        validateNoControlCharacters(
                value,
                field
        );

        if (value.length() > 128) {
            throw new PluginConfigurationException(
                    field + " is too long"
            );
        }

        if (!value.matches(
                "^[a-zA-Z0-9][a-zA-Z0-9_.-]*$"
        )) {
            throw new PluginConfigurationException(
                    "Invalid " + field
            );
        }
    }

    private void validatePlatform(
            String platform) {

        validateNoControlCharacters(
                platform,
                "platform"
        );

        if (platform.length() > 128) {
            throw new PluginConfigurationException(
                    "platform is too long"
            );
        }

        if (!platform.matches(
                "^[a-zA-Z0-9][a-zA-Z0-9._/-]*$"
        )) {
            throw new PluginConfigurationException(
                    "Invalid platform"
            );
        }
    }

    private void validateBuildArgs(
            Map<String, Object> configuration) {

        Object value =
                configuration.get("buildArgs");

        if (value == null) {
            return;
        }

        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new PluginConfigurationException(
                    "buildArgs must be an object"
            );
        }

        for (Map.Entry<?, ?> entry :
                rawMap.entrySet()) {

            if (!(entry.getKey()
                    instanceof String key)) {

                throw new PluginConfigurationException(
                        "Build argument names must be strings"
                );
            }

            if (key.isBlank()) {
                throw new PluginConfigurationException(
                        "Build argument name cannot be blank"
                );
            }

            if (!key.matches(
                    "^[A-Za-z_][A-Za-z0-9_]*$"
            )) {
                throw new PluginConfigurationException(
                        "Invalid build argument name: "
                                + key
                );
            }

            validateNoControlCharacters(
                    key,
                    "build argument name"
            );

            Object argumentValue =
                    entry.getValue();

            if (argumentValue == null) {
                throw new PluginConfigurationException(
                        "Build argument value cannot be null: "
                                + key
                );
            }

            validateNoControlCharacters(
                    String.valueOf(
                            argumentValue
                    ),
                    "build argument value"
            );
        }
    }

    private void validateBoolean(
            Map<String, Object> configuration,
            String field) {

        Object value =
                configuration.get(field);

        if (value != null
                && !(value instanceof Boolean)) {

            throw new PluginConfigurationException(
                    field + " must be boolean"
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

        if (timeout < 1
                || timeout > MAX_TIMEOUT_SECONDS) {

            throw new PluginConfigurationException(
                    "timeoutSeconds must be between 1 and "
                            + MAX_TIMEOUT_SECONDS
            );
        }
    }

    private void validateNoControlCharacters(
            String value,
            String field) {

        if (value.indexOf('\0') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {

            throw new PluginConfigurationException(
                    field
                            + " contains invalid control characters"
            );
        }
    }

    // -------------------------------------------------------------------------
    // Path resolution
    // -------------------------------------------------------------------------

    private Path resolveWorkspaceRoot(
            Map<String, Object> configuration) {

        String value =
                requireString(
                        configuration,
                        "workspaceRoot"
                );

        Path root =
                Path.of(value)
                        .toAbsolutePath()
                        .normalize();

        if (!Files.exists(root)) {
            throw new PluginConfigurationException(
                    "workspaceRoot does not exist: "
                            + root
            );
        }

        if (!Files.isDirectory(root)) {
            throw new PluginConfigurationException(
                    "workspaceRoot must be a directory: "
                            + root
            );
        }

        return root;
    }

    private Path resolveRelativePath(
            Path root,
            String relativePath,
            String field) {

        validateRelativePath(
                relativePath,
                field
        );

        Path resolved =
                root.resolve(
                                relativePath
                        )
                        .normalize();

        if (!resolved.startsWith(root)) {
            throw new PluginConfigurationException(
                    field
                            + " resolves outside workspaceRoot"
            );
        }

        return resolved;
    }

    private Path resolveDockerfile(
            Path contextDirectory,
            String dockerfile) {

        if (dockerfile == null
                || dockerfile.isBlank()) {

            return contextDirectory.resolve(
                    "Dockerfile"
            );
        }

        validateDockerfilePath(
                dockerfile
        );

        Path resolved =
                contextDirectory
                        .resolve(dockerfile)
                        .normalize();

        if (!resolved.startsWith(
                contextDirectory
        )) {
            throw new PluginConfigurationException(
                    "dockerfile resolves outside build context"
            );
        }

        return resolved;
    }

    // -------------------------------------------------------------------------
    // Configuration helpers
    // -------------------------------------------------------------------------

    private String requireString(
            Map<String, Object> configuration,
            String key) {

        Object value =
                configuration.get(key);

        if (!(value instanceof String string)
                || string.isBlank()) {

            throw new PluginConfigurationException(
                    key + " is required"
            );
        }

        return string;
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

        return string;
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

    private Map<String, Object> getBuildArgs(
            Map<String, Object> configuration) {

        Object value =
                configuration.get("buildArgs");

        if (value == null) {
            return Map.of();
        }

        Map<?, ?> raw =
                (Map<?, ?>) value;

        Map<String, Object> result =
                new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry :
                raw.entrySet()) {

            result.put(
                    (String) entry.getKey(),
                    entry.getValue()
            );
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Result helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> baseMetadata(
            Instant start,
            ProcessResult processResult) {

        Map<String, Object> metadata =
                new LinkedHashMap<>();

        metadata.put(
                "operation",
                OPERATION
        );

        metadata.put(
                "success",
                processResult.exitCode() == 0
        );

        metadata.put(
                "exitCode",
                processResult.exitCode()
        );

        metadata.put(
                "timedOut",
                false
        );

        metadata.put(
                "durationMs",
                processResult.durationMs()
        );

        return metadata;
    }

    private PluginResult failureResult(
            Instant start,
            boolean timedOut,
            ProcessResult processResult,
            String output) {

        Map<String, Object> metadata =
                new LinkedHashMap<>();

        metadata.put(
                "operation",
                OPERATION
        );

        metadata.put(
                "success",
                false
        );

        metadata.put(
                "exitCode",
                processResult == null
                        ? -1
                        : processResult.exitCode()
        );

        metadata.put(
                "timedOut",
                timedOut
        );

        metadata.put(
                "durationMs",
                Duration.between(
                        start,
                        Instant.now()
                ).toMillis()
        );

        return PluginResult.builder()
                .success(false)
                .output(
                        output == null
                                ? ""
                                : output
                )
                .metadata(metadata)
                .build();
    }

    private record ProcessResult(
            int exitCode,
            String output,
            long durationMs) {
    }
}