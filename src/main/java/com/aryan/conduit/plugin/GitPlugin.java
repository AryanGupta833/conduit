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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
public class GitPlugin implements WorkflowPlugin {

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 1800;
    private static final int DEFAULT_LOG_COUNT = 10;
    private static final int MAX_LOG_COUNT = 100;

    private static final int MAX_OUTPUT_LENGTH = 16_384;

    private static final List<String> OPERATIONS = List.of(
            "CLONE",
            "FETCH",
            "PULL",
            "CHECKOUT",
            "STATUS",
            "BRANCH",
            "LOG"
    );

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of(
                "GIT",
                "Git",
                "Execute controlled Git repository operations",
                "1.0.0"
        );
    }

    @Override
    public void validateConfiguration(
            Map<String, Object> configuration)
            throws PluginConfigurationException {

        if (configuration == null) {
            throw new PluginConfigurationException(
                    "Git configuration is required"
            );
        }

        String operation = requireString(
                configuration,
                "operation"
        ).toUpperCase();

        if (!OPERATIONS.contains(operation)) {
            throw new PluginConfigurationException(
                    "Unsupported Git operation: " + operation
            );
        }

        validateTimeout(configuration);

        switch (operation) {
            case "CLONE" -> validateClone(configuration);

            case "FETCH",
                 "PULL",
                 "STATUS",
                 "BRANCH",
                 "LOG",
                 "CHECKOUT" ->
                    validateRepositoryOperation(
                            configuration
                    );

            default -> throw new PluginConfigurationException(
                    "Unsupported Git operation: " + operation
            );
        }

        if ("CHECKOUT".equals(operation)) {
            String ref = requireString(
                    configuration,
                    "ref"
            );

            validateRef(ref);
        }

        if ("LOG".equals(operation)) {
            validateLogCount(configuration);
        }
    }

    @Override
    public PluginResult execute(
            PluginContext context)
            throws Exception {

        Map<String, Object> configuration =
                context.configuration();

        validateConfiguration(configuration);

        String operation =
                requireString(
                        configuration,
                        "operation"
                ).toUpperCase();

        return switch (operation) {
            case "CLONE" -> executeClone(configuration);
            case "FETCH" -> executeRepositoryOperation(
                    configuration,
                    "FETCH"
            );
            case "PULL" -> executeRepositoryOperation(
                    configuration,
                    "PULL"
            );
            case "CHECKOUT" -> executeCheckout(
                    configuration
            );
            case "STATUS" -> executeRepositoryOperation(
                    configuration,
                    "STATUS"
            );
            case "BRANCH" -> executeRepositoryOperation(
                    configuration,
                    "BRANCH"
            );
            case "LOG" -> executeLog(configuration);
            default -> throw new PluginConfigurationException(
                    "Unsupported Git operation: " + operation
            );
        };
    }

    // -------------------------------------------------------------------------
    // CLONE
    // -------------------------------------------------------------------------

    private PluginResult executeClone(
            Map<String, Object> configuration)
            throws Exception {

        String repository =
                requireString(
                        configuration,
                        "repository"
                );

        Path workspaceRoot =
                resolveWorkspaceRoot(
                        configuration
                );

        String destinationPath =
                requireString(
                        configuration,
                        "destinationPath"
                );

        Path destination =
                resolveRelativePath(
                        workspaceRoot,
                        destinationPath,
                        "destinationPath"
                );

        if (Files.exists(destination)) {
            throw new PluginConfigurationException(
                    "Clone destination already exists: "
                            + destination
            );
        }

        Files.createDirectories(
                workspaceRoot
        );

        String branch =
                optionalString(
                        configuration,
                        "branch"
                );

        int timeoutSeconds =
                getTimeout(configuration);

        List<String> command =
                new ArrayList<>();

        command.add("git");
        command.add("clone");

        if (branch != null && !branch.isBlank()) {
            validateRef(branch);

            command.add("--branch");
            command.add(branch);
        }

        command.add(repository);
        command.add(destination.toString());

        Instant start = Instant.now();

        ProcessResult processResult;

        try {
            processResult =
                    runGit(
                            command,
                            timeoutSeconds
                    );
        } catch (TimeoutException e) {
            cleanupCloneDestination(destination);

            return failureResult(
                    "CLONE",
                    start,
                    true,
                    null,
                    "Git clone timed out"
            );
        }

        if (processResult.exitCode() != 0) {
            cleanupCloneDestination(destination);

            return failureResult(
                    "CLONE",
                    start,
                    false,
                    processResult,
                    processResult.output()
            );
        }

        Map<String, Object> metadata =
                baseMetadata(
                        "CLONE",
                        start,
                        processResult
                );

        metadata.put(
                "repository",
                repository
        );

        metadata.put(
                "destinationPath",
                destination.toString()
        );

        if (branch != null) {
            metadata.put(
                    "branch",
                    branch
            );
        }

        return PluginResult.builder()
                .success(true)
                .output(processResult.output())
                .metadata(metadata)
                .build();
    }

    // -------------------------------------------------------------------------
    // Repository operations
    // -------------------------------------------------------------------------

    private PluginResult executeRepositoryOperation(
            Map<String, Object> configuration,
            String operation)
            throws Exception {

        Path repository =
                resolveRepository(
                        configuration
                );

        int timeoutSeconds =
                getTimeout(configuration);

        List<String> command =
                buildRepositoryCommand(
                        operation,
                        repository
                );

        Instant start = Instant.now();

        ProcessResult processResult;

        try {
            processResult =
                    runGit(
                            command,
                            timeoutSeconds
                    );
        } catch (TimeoutException e) {
            return failureResult(
                    operation,
                    start,
                    true,
                    null,
                    "Git operation timed out"
            );
        }

        Map<String, Object> metadata =
                baseMetadata(
                        operation,
                        start,
                        processResult
                );

        if ("LOG".equals(operation)) {
            metadata.put(
                    "count",
                    getLogCount(configuration)
            );
        }

        return PluginResult.builder()
                .success(processResult.exitCode() == 0)
                .output(processResult.output())
                .metadata(metadata)
                .build();
    }

    private PluginResult executeCheckout(
            Map<String, Object> configuration)
            throws Exception {

        Path repository =
                resolveRepository(
                        configuration
                );

        String ref =
                requireString(
                        configuration,
                        "ref"
                );

        validateRef(ref);

        int timeoutSeconds =
                getTimeout(configuration);

        /*
         * IMPORTANT:
         *
         * Do NOT use:
         *
         *     git checkout -- <ref>
         *
         * because `--` makes Git interpret <ref> as a pathspec.
         *
         * For branch/ref checkout we need:
         *
         *     git checkout <ref>
         */
        List<String> command = List.of(
                "git",
                "-C",
                repository.toString(),
                "checkout",
                ref
        );

        Instant start = Instant.now();

        ProcessResult processResult;

        try {
            processResult =
                    runGit(
                            command,
                            timeoutSeconds
                    );
        } catch (TimeoutException e) {
            return failureResult(
                    "CHECKOUT",
                    start,
                    true,
                    null,
                    "Git checkout timed out"
            );
        }

        Map<String, Object> metadata =
                baseMetadata(
                        "CHECKOUT",
                        start,
                        processResult
                );

        metadata.put(
                "ref",
                ref
        );

        return PluginResult.builder()
                .success(processResult.exitCode() == 0)
                .output(processResult.output())
                .metadata(metadata)
                .build();
    }

    private PluginResult executeLog(
            Map<String, Object> configuration)
            throws Exception {

        Path repository =
                resolveRepository(
                        configuration
                );

        int count =
                getLogCount(configuration);

        int timeoutSeconds =
                getTimeout(configuration);

        List<String> command = List.of(
                "git",
                "-C",
                repository.toString(),
                "log",
                "-n",
                String.valueOf(count),
                "--oneline",
                "--decorate"
        );

        Instant start = Instant.now();

        ProcessResult processResult;

        try {
            processResult =
                    runGit(
                            command,
                            timeoutSeconds
                    );
        } catch (TimeoutException e) {
            return failureResult(
                    "LOG",
                    start,
                    true,
                    null,
                    "Git log timed out"
            );
        }

        Map<String, Object> metadata =
                baseMetadata(
                        "LOG",
                        start,
                        processResult
                );

        metadata.put(
                "count",
                count
        );

        return PluginResult.builder()
                .success(processResult.exitCode() == 0)
                .output(processResult.output())
                .metadata(metadata)
                .build();
    }

    private List<String> buildRepositoryCommand(
            String operation,
            Path repository) {

        return switch (operation) {

            case "FETCH" -> List.of(
                    "git",
                    "-C",
                    repository.toString(),
                    "fetch",
                    "--prune",
                    "--tags"
            );

            case "PULL" -> List.of(
                    "git",
                    "-C",
                    repository.toString(),
                    "pull",
                    "--ff-only"
            );

            case "STATUS" -> List.of(
                    "git",
                    "-C",
                    repository.toString(),
                    "status",
                    "--short",
                    "--branch"
            );

            case "BRANCH" -> List.of(
                    "git",
                    "-C",
                    repository.toString(),
                    "branch",
                    "--list"
            );

            default -> throw new PluginConfigurationException(
                    "Unsupported repository operation: "
                            + operation
            );
        };
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private void validateClone(
            Map<String, Object> configuration) {

        requireString(
                configuration,
                "repository"
        );

        requireWorkspaceRoot(
                configuration
        );

        String destinationPath =
                requireString(
                        configuration,
                        "destinationPath"
                );

        validateRelativePath(
                destinationPath,
                "destinationPath"
        );

        String branch =
                optionalString(
                        configuration,
                        "branch"
                );

        if (branch != null) {
            validateRef(branch);
        }

        validateRepositoryUrl(
                configuration
        );
    }

    private void validateRepositoryOperation(
            Map<String, Object> configuration) {

        requireWorkspaceRoot(
                configuration
        );

        String repositoryPath =
                requireString(
                        configuration,
                        "repositoryPath"
                );

        /*
         * This is important:
         *
         * repositoryPath is resolved relative to workspaceRoot.
         * Therefore "../repo" must be rejected.
         */
        validateRelativePath(
                repositoryPath,
                "repositoryPath"
        );
    }

    private void validateRepositoryUrl(
            Map<String, Object> configuration) {

        String repository =
                requireString(
                        configuration,
                        "repository"
                );

        if (repository.contains("\n")
                || repository.contains("\r")
                || repository.contains("\0")) {

            throw new PluginConfigurationException(
                    "Repository contains invalid control characters"
            );
        }

        if (repository.matches(
                "^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*:[^/]*@.*$"
        )) {

            throw new PluginConfigurationException(
                    "Repository URLs with embedded credentials are not allowed"
            );
        }

        if (repository.startsWith("ext::")
                || repository.contains("ext::")) {

            throw new PluginConfigurationException(
                    "Git ext:: transport is not allowed"
            );
        }
    }

    private void validateRef(
            String ref) {

        if (ref == null || ref.isBlank()) {
            throw new PluginConfigurationException(
                    "Git ref is required"
            );
        }

        if (ref.startsWith("-")) {
            throw new PluginConfigurationException(
                    "Git ref cannot start with '-'"
            );
        }

        if (ref.contains("\n")
                || ref.contains("\r")
                || ref.contains("\0")) {

            throw new PluginConfigurationException(
                    "Git ref contains invalid control characters"
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
            path = Path.of(value);
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

        /*
         * Explicitly reject traversal segments.
         *
         * This prevents values such as:
         *
         * ../repo
         * foo/../../repo
         */
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                throw new PluginConfigurationException(
                        field
                                + " cannot contain '..' traversal segments"
                );
            }
        }

        if (value.contains("\0")
                || value.contains("\n")
                || value.contains("\r")) {

            throw new PluginConfigurationException(
                    field + " contains invalid control characters"
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

        int timeout;

        try {
            timeout =
                    ((Number) value).intValue();
        } catch (Exception e) {
            throw new PluginConfigurationException(
                    "timeoutSeconds must be numeric",
                    e
            );
        }

        if (timeout < 1
                || timeout > MAX_TIMEOUT_SECONDS) {

            throw new PluginConfigurationException(
                    "timeoutSeconds must be between 1 and "
                            + MAX_TIMEOUT_SECONDS
            );
        }
    }

    private void validateLogCount(
            Map<String, Object> configuration) {

        Object value =
                configuration.get("count");

        if (value == null) {
            return;
        }

        int count;

        try {
            count =
                    ((Number) value).intValue();
        } catch (Exception e) {
            throw new PluginConfigurationException(
                    "count must be numeric",
                    e
            );
        }

        if (count < 1
                || count > MAX_LOG_COUNT) {

            throw new PluginConfigurationException(
                    "count must be between 1 and "
                            + MAX_LOG_COUNT
            );
        }
    }

    // -------------------------------------------------------------------------
    // Paths
    // -------------------------------------------------------------------------

    private Path resolveRepository(
            Map<String, Object> configuration) {

        Path workspaceRoot =
                resolveWorkspaceRoot(
                        configuration
                );

        String repositoryPath =
                requireString(
                        configuration,
                        "repositoryPath"
                );

        validateRelativePath(
                repositoryPath,
                "repositoryPath"
        );

        Path repository =
                resolveRelativePath(
                        workspaceRoot,
                        repositoryPath,
                        "repositoryPath"
                );

        if (!Files.exists(repository)) {
            throw new PluginConfigurationException(
                    "Repository does not exist: "
                            + repository
            );
        }

        if (!Files.isDirectory(repository)) {
            throw new PluginConfigurationException(
                    "Repository path is not a directory: "
                            + repository
            );
        }

        if (!Files.isDirectory(
                repository.resolve(".git")
        )) {
            throw new PluginConfigurationException(
                    "Path is not a Git repository: "
                            + repository
            );
        }

        return repository;
    }

    private Path resolveWorkspaceRoot(
            Map<String, Object> configuration) {

        String root =
                requireWorkspaceRoot(
                        configuration
                );

        try {
            Path path =
                    Path.of(root)
                            .toAbsolutePath()
                            .normalize();

            Files.createDirectories(path);

            return path;
        } catch (Exception e) {
            throw new PluginConfigurationException(
                    "Invalid workspaceRoot",
                    e
            );
        }
    }

    private String requireWorkspaceRoot(
            Map<String, Object> configuration) {

        return requireString(
                configuration,
                "workspaceRoot"
        );
    }

    private Path resolveRelativePath(
            Path workspaceRoot,
            String relativePath,
            String field) {

        validateRelativePath(
                relativePath,
                field
        );

        Path root =
                workspaceRoot
                        .toAbsolutePath()
                        .normalize();

        Path resolved =
                root.resolve(relativePath)
                        .normalize();

        if (!resolved.startsWith(root)) {
            throw new PluginConfigurationException(
                    field
                            + " resolves outside workspaceRoot"
            );
        }

        return resolved;
    }

    // -------------------------------------------------------------------------
    // Process execution
    // -------------------------------------------------------------------------

    private ProcessResult runGit(
            List<String> command,
            int timeoutSeconds)
            throws Exception {

        ProcessBuilder processBuilder =
                new ProcessBuilder(command);

        /*
         * Prevent Git's ext:: protocol transport from being
         * enabled through environment/configuration.
         */
        Map<String, String> environment =
                processBuilder.environment();

        environment.put(
                "GIT_CONFIG_COUNT",
                "1"
        );

        environment.put(
                "GIT_CONFIG_KEY_0",
                "protocol.ext.allow"
        );

        environment.put(
                "GIT_CONFIG_VALUE_0",
                "never"
        );

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

                        while ((line = reader.readLine()) != null) {

                            synchronized (output) {

                                if (output.length()
                                        < MAX_OUTPUT_LENGTH) {

                                    int remaining =
                                            MAX_OUTPUT_LENGTH
                                                    - output.length();

                                    if (line.length()
                                            + System.lineSeparator()
                                            .length()
                                            <= remaining) {

                                        output.append(line)
                                                .append(
                                                        System.lineSeparator()
                                                );

                                    } else {

                                        output.append(
                                                line,
                                                0,
                                                Math.max(
                                                        0,
                                                        remaining
                                                )
                                        );
                                    }
                                }
                            }
                        }

                    } catch (Exception e) {
                        log.debug(
                                "Git output reader stopped",
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
                    "Git command timed out"
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
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> baseMetadata(
            String operation,
            Instant start,
            ProcessResult processResult) {

        Map<String, Object> metadata =
                new LinkedHashMap<>();

        metadata.put(
                "operation",
                operation
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
            String operation,
            Instant start,
            boolean timedOut,
            ProcessResult processResult,
            String output) {

        Map<String, Object> metadata =
                new LinkedHashMap<>();

        metadata.put(
                "operation",
                operation
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
                .output(output == null ? "" : output)
                .metadata(metadata)
                .build();
    }

    private void cleanupCloneDestination(
            Path destination) {

        if (!Files.exists(destination)) {
            return;
        }

        try (Stream<Path> paths =
                     Files.walk(destination)) {

            paths.sorted(
                            (a, b) ->
                                    b.getNameCount()
                                            - a.getNameCount()
                    )
                    .forEach(path -> {

                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            log.warn(
                                    "Failed to clean clone destination {}",
                                    path,
                                    e
                            );
                        }
                    });

        } catch (Exception e) {
            log.warn(
                    "Failed to clean clone destination {}",
                    destination,
                    e
            );
        }
    }

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

    private int getTimeout(
            Map<String, Object> configuration) {

        Object value =
                configuration.get("timeoutSeconds");

        if (value == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }

        return ((Number) value).intValue();
    }

    private int getLogCount(
            Map<String, Object> configuration) {

        Object value =
                configuration.get("count");

        if (value == null) {
            return DEFAULT_LOG_COUNT;
        }

        return ((Number) value).intValue();
    }

    private record ProcessResult(
            int exitCode,
            String output,
            long durationMs) {
    }
}