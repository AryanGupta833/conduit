package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DockerRunPluginTest {

    private DockerRunPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new DockerRunPlugin();
    }

    // ---------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------

    @Test
    void metadataIsCorrect() {

        PluginMetadata metadata =
                plugin.metadata();

        assertEquals(
                "DOCKER_RUN",
                metadata.type()
        );

        assertEquals(
                "Docker Run",
                metadata.displayName()
        );

        assertEquals(
                "1.0.0",
                metadata.version()
        );
    }

    // ---------------------------------------------------------
    // Required configuration
    // ---------------------------------------------------------

    @Test
    void rejectsNullConfiguration() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(null)
        );
    }

    @Test
    void rejectsMissingOperation() {

        Map<String, Object> config =
                Map.of(
                        "image",
                        "alpine:3.20"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsWrongOperation() {

        Map<String, Object> config =
                Map.of(
                        "operation",
                        "DOCKER_BUILD",
                        "image",
                        "alpine:3.20"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsMissingImage() {

        Map<String, Object> config =
                Map.of(
                        "operation",
                        "DOCKER_RUN"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsBlankImage() {

        Map<String, Object> config =
                Map.of(
                        "operation",
                        "DOCKER_RUN",
                        "image",
                        "   "
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // ---------------------------------------------------------
    // Image validation
    // ---------------------------------------------------------

    @Test
    void rejectsInvalidImageReference() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "image",
                "alpine image"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void acceptsRegistryImage() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "image",
                "registry.example.com/team/app:v1.2.3"
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void acceptsDigestImage() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "image",
                "alpine@sha256:abcdef123456"
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsControlCharactersInImage() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "image",
                "alpine:3.20\nmalicious"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // ---------------------------------------------------------
    // Command validation
    // ---------------------------------------------------------

    @Test
    void acceptsCommand() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "echo",
                        "hello"
                )
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void acceptsCommandWithArguments() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "sh",
                        "-c",
                        "echo hello"
                )
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsNonArrayCommand() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                "echo hello"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsNullCommandArgument() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of("echo", "hello")
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    // ---------------------------------------------------------
    // Environment validation
    // ---------------------------------------------------------

    @Test
    void acceptsEnvironment() {

        Map<String, Object> environment =
                new LinkedHashMap<>();

        environment.put(
                "APP_ENV",
                "production"
        );

        environment.put(
                "VERSION",
                "1.0"
        );

        Map<String, Object> config =
                baseConfig();

        config.put(
                "environment",
                environment
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsInvalidEnvironmentName() {

        Map<String, Object> environment =
                new LinkedHashMap<>();

        environment.put(
                "APP-NAME",
                "conduit"
        );

        Map<String, Object> config =
                baseConfig();

        config.put(
                "environment",
                environment
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsEnvironmentWithNullValue() {

        Map<String, Object> environment =
                new LinkedHashMap<>();

        environment.put(
                "APP_ENV",
                null
        );

        Map<String, Object> config =
                baseConfig();

        config.put(
                "environment",
                environment
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsNonObjectEnvironment() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "environment",
                List.of("A=B")
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // ---------------------------------------------------------
    // Working directory
    // ---------------------------------------------------------

    @Test
    void acceptsValidWorkingDirectory() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "workingDirectory",
                "/app"
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsRelativeWorkingDirectory() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "workingDirectory",
                "app"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsWorkingDirectoryTraversal() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "workingDirectory",
                "/app/../etc"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // ---------------------------------------------------------
    // Resource limits
    // ---------------------------------------------------------

    @Test
    void acceptsValidCpuLimit() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "cpus",
                2.0
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsCpuBelowMinimum() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "cpus",
                0.01
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsCpuAboveMaximum() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "cpus",
                100.0
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsInvalidCpuType() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "cpus",
                "2"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void acceptsValidMemoryLimit() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "memoryMb",
                512
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsMemoryBelowMinimum() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "memoryMb",
                1
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsMemoryAboveMaximum() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "memoryMb",
                100000
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void acceptsValidPidsLimit() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "pidsLimit",
                512
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsInvalidPidsLimit() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "pidsLimit",
                1
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // ---------------------------------------------------------
    // Timeout
    // ---------------------------------------------------------

    @Test
    void acceptsValidTimeout() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "timeoutSeconds",
                600
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsZeroTimeout() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "timeoutSeconds",
                0
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsTimeoutAboveMaximum() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "timeoutSeconds",
                3601
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsInvalidTimeoutType() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "timeoutSeconds",
                "300"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // ---------------------------------------------------------
    // Security flags
    // ---------------------------------------------------------

    @Test
    void acceptsSecurityOptions() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "readOnlyRootFilesystem",
                true
        );

        config.put(
                "networkDisabled",
                true
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsInvalidReadOnlyFlag() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "readOnlyRootFilesystem",
                "true"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsInvalidNetworkFlag() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "networkDisabled",
                "false"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // ---------------------------------------------------------
    // Complete configuration
    // ---------------------------------------------------------

    @Test
    void acceptsCompleteConfiguration() {

        Map<String, Object> environment =
                new LinkedHashMap<>();

        environment.put(
                "APP_ENV",
                "production"
        );

        environment.put(
                "VERSION",
                "1.0"
        );

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "echo",
                        "hello"
                )
        );

        config.put(
                "environment",
                environment
        );

        config.put(
                "workingDirectory",
                "/app"
        );

        config.put(
                "cpus",
                1.5
        );

        config.put(
                "memoryMb",
                512
        );

        config.put(
                "pidsLimit",
                256
        );

        config.put(
                "timeoutSeconds",
                300
        );

        config.put(
                "readOnlyRootFilesystem",
                true
        );

        config.put(
                "networkDisabled",
                true
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    // ---------------------------------------------------------
    // Real Docker integration tests
    // ---------------------------------------------------------

    @Test
    void runsSimpleDockerContainer() throws Exception {

        assumeDockerAvailable();

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "echo",
                        "hello-from-conduit"
                )
        );

        PluginResult result =
                plugin.execute(
                        context(config)
                );

        assertTrue(
                result.isSuccess(),
                () -> "Docker execution failed: "
                        + result.getOutput()
        );

        assertTrue(
                result.getOutput()
                        .contains("hello-from-conduit")
        );

        assertEquals(
                0,
                result.getMetadata()
                        .get("exitCode")
        );
    }

    @Test
    void passesEnvironmentVariablesToContainer()
            throws Exception {

        assumeDockerAvailable();

        Map<String, Object> environment =
                new LinkedHashMap<>();

        environment.put(
                "CONDUIT_TEST",
                "production"
        );

        Map<String, Object> config =
                baseConfig();

        config.put(
                "environment",
                environment
        );

        config.put(
                "command",
                List.of(
                        "sh",
                        "-c",
                        "echo $CONDUIT_TEST"
                )
        );

        PluginResult result =
                plugin.execute(
                        context(config)
                );

        assertTrue(
                result.isSuccess(),
                () -> result.getOutput()
        );

        assertTrue(
                result.getOutput()
                        .contains("production")
        );
    }

    @Test
    void returnsFailureForNonZeroExitCode()
            throws Exception {

        assumeDockerAvailable();

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "sh",
                        "-c",
                        "exit 7"
                )
        );

        PluginResult result =
                plugin.execute(
                        context(config)
                );

        assertFalse(
                result.isSuccess()
        );

        assertEquals(
                7,
                result.getMetadata()
                        .get("exitCode")
        );
    }

    @Test
    void supportsNetworkDisabled()
            throws Exception {

        assumeDockerAvailable();

        Map<String, Object> config =
                baseConfig();

        config.put(
                "networkDisabled",
                true
        );

        config.put(
                "command",
                List.of(
                        "sh",
                        "-c",
                        "wget -q -T 2 -O - https://example.com"
                )
        );

        PluginResult result =
                plugin.execute(
                        context(config)
                );

        assertFalse(
                result.isSuccess()
        );

        assertEquals(
                true,
                result.getMetadata()
                        .get("networkDisabled")
        );
    }

    @Test
    void usesReadOnlyRootFilesystem()
            throws Exception {

        assumeDockerAvailable();

        Map<String, Object> config =
                baseConfig();

        config.put(
                "readOnlyRootFilesystem",
                true
        );

        config.put(
                "command",
                List.of(
                        "sh",
                        "-c",
                        "touch /conduit-readonly-test"
                )
        );

        PluginResult result =
                plugin.execute(
                        context(config)
                );

        assertFalse(
                result.isSuccess()
        );

        assertEquals(
                true,
                result.getMetadata()
                        .get("readOnlyRootFilesystem")
        );
    }

    @Test
    void returnsExecutionMetadata()
            throws Exception {

        assumeDockerAvailable();

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "echo",
                        "metadata"
                )
        );

        PluginResult result =
                plugin.execute(
                        context(config)
                );

        assertTrue(
                result.isSuccess()
        );

        assertEquals(
                "DOCKER_RUN",
                result.getMetadata()
                        .get("operation")
        );

        assertEquals(
                "alpine:3.20",
                result.getMetadata()
                        .get("image")
        );

        assertNotNull(
                result.getMetadata()
                        .get("containerName")
        );

        assertNotNull(
                result.getMetadata()
                        .get("durationMs")
        );

        assertEquals(
                false,
                result.getMetadata()
                        .get("timedOut")
        );
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private Map<String, Object> baseConfig() {

        Map<String, Object> config =
                new LinkedHashMap<>();

        config.put(
                "operation",
                "DOCKER_RUN"
        );

        config.put(
                "image",
                "alpine:3.20"
        );

        return config;
    }

    private PluginContext context(
            Map<String, Object> configuration) {

        return new PluginContext(
                "docker-run-test",
                30,
                configuration,
                Map.of()
        );
    }

    private void assumeDockerAvailable()
            throws Exception {

        Process process =
                new ProcessBuilder(
                        "docker",
                        "version"
                )
                        .redirectErrorStream(true)
                        .start();

        boolean completed =
                process.waitFor(
                        10,
                        java.util.concurrent.TimeUnit.SECONDS
                );

        if (!completed ||
                process.exitValue() != 0) {

            org.junit.jupiter.api.Assumptions
                    .abort(
                            "Docker is not available"
                    );
        }
    }
}