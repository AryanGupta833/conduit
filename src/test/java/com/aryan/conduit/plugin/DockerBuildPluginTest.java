package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DockerBuildPluginTest {

    private DockerBuildPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new DockerBuildPlugin();
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    void exposesCorrectMetadata() {

        var metadata = plugin.metadata();

        assertEquals(
                "DOCKER_BUILD",
                metadata.type()
        );

        assertEquals(
                "Docker Build",
                metadata.displayName()
        );

        assertEquals(
                "1.0.0",
                metadata.version()
        );
    }

    // -------------------------------------------------------------------------
    // Validation - basic configuration
    // -------------------------------------------------------------------------

    @Test
    void rejectsNullConfiguration() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(null)
        );
    }

    @Test
    void rejectsMissingOperation() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of()
                )
        );
    }

    @Test
    void rejectsWrongOperation() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_RUN"
                        )
                )
        );
    }

    @Test
    void rejectsMissingImage(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app"
                        )
                )
        );
    }

    @Test
    void rejectsBlankImage(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                " "
                        )
                )
        );
    }

    @Test
    void rejectsInvalidImageCharacters(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "my image:latest"
                        )
                )
        );
    }

    @Test
    void rejectsImageStartingWithDash(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "--tag"
                        )
                )
        );
    }

    // -------------------------------------------------------------------------
    // Workspace validation
    // -------------------------------------------------------------------------

    @Test
    void rejectsMissingWorkspaceRoot() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest"
                        )
                )
        );
    }

    @Test
    void rejectsRelativeWorkspaceRoot() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                "workspace",
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest"
                        )
                )
        );
    }

    @Test
    void rejectsMissingContextPath(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "image",
                                "conduit/test:latest"
                        )
                )
        );
    }

    @Test
    void rejectsAbsoluteContextPath(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                tempDir.resolve("app").toString(),
                                "image",
                                "conduit/test:latest"
                        )
                )
        );
    }

    @Test
    void rejectsContextPathTraversal(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "../app",
                                "image",
                                "conduit/test:latest"
                        )
                )
        );
    }

    @Test
    void rejectsNestedContextPathTraversal(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app/../../outside",
                                "image",
                                "conduit/test:latest"
                        )
                )
        );
    }

    // -------------------------------------------------------------------------
    // Dockerfile validation
    // -------------------------------------------------------------------------

    @Test
    void acceptsOmittedDockerfile(
            @TempDir Path tempDir) {

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest"
                        )
                )
        );
    }

    @Test
    void rejectsAbsoluteDockerfile(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "dockerfile",
                                tempDir
                                        .resolve("Dockerfile")
                                        .toString(),
                                "image",
                                "conduit/test:latest"
                        )
                )
        );
    }

    @Test
    void rejectsDockerfileTraversal(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "dockerfile",
                                "../Dockerfile",
                                "image",
                                "conduit/test:latest"
                        )
                )
        );
    }

    @Test
    void acceptsNestedDockerfile(
            @TempDir Path tempDir) {

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "dockerfile",
                                "docker/Dockerfile",
                                "image",
                                "conduit/test:latest"
                        )
                )
        );
    }

    // -------------------------------------------------------------------------
    // Target / platform
    // -------------------------------------------------------------------------

    @Test
    void acceptsValidTarget(
            @TempDir Path tempDir) {

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "target",
                                "production"
                        )
                )
        );
    }

    @Test
    void rejectsInvalidTarget(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "target",
                                "production target"
                        )
                )
        );
    }

    @Test
    void acceptsValidPlatform(
            @TempDir Path tempDir) {

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "platform",
                                "linux/amd64"
                        )
                )
        );
    }

    @Test
    void rejectsInvalidPlatform(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "platform",
                                "linux amd64"
                        )
                )
        );
    }

    // -------------------------------------------------------------------------
    // Build arguments
    // -------------------------------------------------------------------------

    @Test
    void acceptsValidBuildArguments(
            @TempDir Path tempDir) {

        Map<String, Object> buildArgs =
                new LinkedHashMap<>();

        buildArgs.put(
                "APP_ENV",
                "production"
        );

        buildArgs.put(
                "VERSION",
                "1.0"
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "buildArgs",
                                buildArgs
                        )
                )
        );
    }

    @Test
    void rejectsNonMapBuildArguments(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "buildArgs",
                                "APP_ENV=production"
                        )
                )
        );
    }

    @Test
    void rejectsInvalidBuildArgumentName(
            @TempDir Path tempDir) {

        Map<String, Object> buildArgs =
                Map.of(
                        "APP-VALUE",
                        "production"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "buildArgs",
                                buildArgs
                        )
                )
        );
    }

    @Test
    void rejectsBuildArgumentStartingWithNumber(
            @TempDir Path tempDir) {

        Map<String, Object> buildArgs =
                Map.of(
                        "123VALUE",
                        "production"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "buildArgs",
                                buildArgs
                        )
                )
        );
    }

    @Test
    void rejectsNullBuildArgumentValue(
            @TempDir Path tempDir) {

        Map<String, Object> buildArgs =
                new LinkedHashMap<>();

        buildArgs.put(
                "APP_ENV",
                null
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "buildArgs",
                                buildArgs
                        )
                )
        );
    }

    @Test
    void rejectsBuildArgumentContainingNewline(
            @TempDir Path tempDir) {

        Map<String, Object> buildArgs =
                Map.of(
                        "APP_ENV",
                        "production\nmalicious"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "buildArgs",
                                buildArgs
                        )
                )
        );
    }

    // -------------------------------------------------------------------------
    // Boolean / timeout validation
    // -------------------------------------------------------------------------

    @Test
    void acceptsBooleanOptions(
            @TempDir Path tempDir) {

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "noCache",
                                true,
                                "pull",
                                false
                        )
                )
        );
    }

    @Test
    void rejectsNonBooleanNoCache(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "noCache",
                                "true"
                        )
                )
        );
    }

    @Test
    void rejectsNonBooleanPull(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "pull",
                                "false"
                        )
                )
        );
    }

    @Test
    void rejectsZeroTimeout(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "timeoutSeconds",
                                0
                        )
                )
        );
    }

    @Test
    void rejectsExcessiveTimeout(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "timeoutSeconds",
                                3601
                        )
                )
        );
    }

    @Test
    void rejectsNonNumericTimeout(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest",
                                "timeoutSeconds",
                                "600"
                        )
                )
        );
    }

    // -------------------------------------------------------------------------
    // Configuration with full valid setup
    // -------------------------------------------------------------------------

    @Test
    void acceptsCompleteValidConfiguration(
            @TempDir Path tempDir) {

        Map<String, Object> buildArgs =
                new LinkedHashMap<>();

        buildArgs.put(
                "APP_ENV",
                "production"
        );

        buildArgs.put(
                "VERSION",
                "1.0"
        );

        Map<String, Object> configuration =
                new LinkedHashMap<>();

        configuration.put(
                "operation",
                "DOCKER_BUILD"
        );

        configuration.put(
                "workspaceRoot",
                tempDir.toString()
        );

        configuration.put(
                "contextPath",
                "app"
        );

        configuration.put(
                "dockerfile",
                "Dockerfile"
        );

        configuration.put(
                "image",
                "conduit/test:latest"
        );

        configuration.put(
                "buildArgs",
                buildArgs
        );

        configuration.put(
                "target",
                "production"
        );

        configuration.put(
                "platform",
                "linux/amd64"
        );

        configuration.put(
                "noCache",
                true
        );

        configuration.put(
                "pull",
                false
        );

        configuration.put(
                "timeoutSeconds",
                600
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(
                        configuration
                )
        );
    }

    // -------------------------------------------------------------------------
    // Execution validation
    // -------------------------------------------------------------------------

    @Test
    void rejectsMissingBuildContextAtExecution(
            @TempDir Path tempDir) {

        PluginContext context =
                context(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "missing",
                                "image",
                                "conduit/test:latest"
                        )
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsBuildContextThatIsFile(
            @TempDir Path tempDir)
            throws Exception {

        Files.writeString(
                tempDir.resolve("context"),
                "not a directory"
        );

        PluginContext context =
                context(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "context",
                                "image",
                                "conduit/test:latest"
                        )
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsMissingDockerfileAtExecution(
            @TempDir Path tempDir)
            throws Exception {

        Path context =
                tempDir.resolve("app");

        Files.createDirectories(context);

        PluginContext pluginContext =
                context(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                "conduit/test:latest"
                        )
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(pluginContext)
        );
    }

    @Test
    void rejectsExplicitMissingDockerfile(
            @TempDir Path tempDir)
            throws Exception {

        Path context =
                tempDir.resolve("app");

        Files.createDirectories(context);

        PluginContext pluginContext =
                context(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "dockerfile",
                                "Dockerfile.production",
                                "image",
                                "conduit/test:latest"
                        )
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(pluginContext)
        );
    }

    // -------------------------------------------------------------------------
    // Real Docker integration
    // -------------------------------------------------------------------------

    @Test
    void buildsSimpleDockerImageWhenDockerIsAvailable(
            @TempDir Path tempDir)
            throws Exception {

        assumeDockerAvailable();

        Path context =
                createDockerContext(
                        tempDir
                );

        String image =
                "conduit-test:plugin-build";

        PluginContext pluginContext =
                context(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                image,
                                "noCache",
                                true,
                                "timeoutSeconds",
                                300
                        )
                );

        var result =
                plugin.execute(pluginContext);

        assertTrue(
                result.isSuccess(),
                "Docker build failed:\n"
                        + result.getOutput()
        );

        assertEquals(
                "DOCKER_BUILD",
                result.getMetadata()
                        .get("operation")
        );

        assertEquals(
                image,
                result.getMetadata()
                        .get("image")
        );

        assertEquals(
                0,
                result.getMetadata()
                        .get("exitCode")
        );

        assertEquals(
                false,
                result.getMetadata()
                        .get("timedOut")
        );

        assertNotNull(
                result.getMetadata()
                        .get("durationMs")
        );

        assertDockerImageExists(
                image
        );

        removeDockerImage(
                image
        );
    }

    @Test
    void buildsImageWithBuildArguments(
            @TempDir Path tempDir)
            throws Exception {

        assumeDockerAvailable();

        Path context =
                tempDir.resolve("app");

        Files.createDirectories(context);

        Files.writeString(
                context.resolve("Dockerfile"),
                """
                FROM alpine:3.20
                ARG APP_ENV
                RUN test "$APP_ENV" = "production"
                CMD ["echo", "conduit"]
                """
        );

        String image =
                "conduit-test:build-args";

        Map<String, Object> buildArgs =
                new LinkedHashMap<>();

        buildArgs.put(
                "APP_ENV",
                "production"
        );

        PluginContext pluginContext =
                context(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "image",
                                image,
                                "buildArgs",
                                buildArgs,
                                "noCache",
                                true,
                                "timeoutSeconds",
                                300
                        )
                );

        var result =
                plugin.execute(pluginContext);

        assertTrue(
                result.isSuccess(),
                "Docker build with build args failed:\n"
                        + result.getOutput()
        );

        assertEquals(
                0,
                result.getMetadata()
                        .get("exitCode")
        );

        assertDockerImageExists(
                image
        );

        removeDockerImage(
                image
        );
    }

    @Test
    void buildsSpecifiedDockerfile(
            @TempDir Path tempDir)
            throws Exception {

        assumeDockerAvailable();

        Path context =
                tempDir.resolve("app");

        Files.createDirectories(context);

        Files.writeString(
                context.resolve("Dockerfile.custom"),
                """
                FROM alpine:3.20
                RUN echo "custom dockerfile"
                """
        );

        String image =
                "conduit-test:custom-dockerfile";

        PluginContext pluginContext =
                context(
                        Map.of(
                                "operation",
                                "DOCKER_BUILD",
                                "workspaceRoot",
                                tempDir.toString(),
                                "contextPath",
                                "app",
                                "dockerfile",
                                "Dockerfile.custom",
                                "image",
                                image,
                                "noCache",
                                true,
                                "timeoutSeconds",
                                300
                        )
                );

        var result =
                plugin.execute(pluginContext);

        assertTrue(
                result.isSuccess(),
                "Custom Dockerfile build failed:\n"
                        + result.getOutput()
        );

        assertEquals(
                0,
                result.getMetadata()
                        .get("exitCode")
        );

        assertDockerImageExists(
                image
        );

        removeDockerImage(
                image
        );
    }

    // -------------------------------------------------------------------------
    // Docker helpers
    // -------------------------------------------------------------------------

    private Path createDockerContext(
            Path tempDir)
            throws Exception {

        Path context =
                tempDir.resolve("app");

        Files.createDirectories(context);

        Files.writeString(
                context.resolve("Dockerfile"),
                """
                FROM alpine:3.20
                RUN echo "Conduit Docker Build Plugin"
                CMD ["echo", "conduit"]
                """
        );

        return context;
    }

    private void assumeDockerAvailable()
            throws Exception {

        Process process =
                new ProcessBuilder(
                        "docker",
                        "version",
                        "--format",
                        "{{.Server.Version}}"
                )
                        .redirectErrorStream(true)
                        .start();

        boolean completed =
                process.waitFor(
                        15,
                        TimeUnit.SECONDS
                );

        if (!completed) {
            process.destroyForcibly();

            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false,
                    "Docker CLI is not responding"
            );

            return;
        }

        if (process.exitValue() != 0) {

            String output =
                    new String(
                            process.getInputStream()
                                    .readAllBytes(),
                            StandardCharsets.UTF_8
                    );

            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false,
                    "Docker is unavailable: "
                            + output
            );
        }
    }

    private void assertDockerImageExists(
            String image)
            throws Exception {

        Process process =
                new ProcessBuilder(
                        "docker",
                        "image",
                        "inspect",
                        image
                )
                        .redirectErrorStream(true)
                        .start();

        boolean completed =
                process.waitFor(
                        30,
                        TimeUnit.SECONDS
                );

        assertTrue(
                completed,
                "docker image inspect timed out"
        );

        String output =
                new String(
                        process.getInputStream()
                                .readAllBytes(),
                        StandardCharsets.UTF_8
                );

        assertEquals(
                0,
                process.exitValue(),
                "Docker image does not exist: "
                        + image
                        + "\n"
                        + output
        );
    }

    private void removeDockerImage(
            String image)
            throws Exception {

        Process process =
                new ProcessBuilder(
                        "docker",
                        "image",
                        "rm",
                        "-f",
                        image
                )
                        .redirectErrorStream(true)
                        .start();

        boolean completed =
                process.waitFor(
                        30,
                        TimeUnit.SECONDS
                );

        assertTrue(
                completed,
                "docker image rm timed out"
        );

        if (process.exitValue() != 0) {

            String output =
                    new String(
                            process.getInputStream()
                                    .readAllBytes(),
                            StandardCharsets.UTF_8
                    );

            fail(
                    "Failed to remove test image "
                            + image
                            + ":\n"
                            + output
            );
        }
    }

    private PluginContext context(
            Map<String, Object> configuration) {

        return new PluginContext(
                "docker-build-test",
                30,
                configuration,
                Map.of()
        );
    }
}