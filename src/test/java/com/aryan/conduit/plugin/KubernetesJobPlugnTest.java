package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KubernetesJobPluginTest {

    private KubernetesJobPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new KubernetesJobPlugin(
                new ObjectMapper()
        );
    }

    // =========================================================
    // Metadata
    // =========================================================

    @Test
    void metadataIsCorrect() {

        PluginMetadata metadata =
                plugin.metadata();

        assertEquals(
                "KUBERNETES_JOB",
                metadata.type()
        );

        assertEquals(
                "Kubernetes Job",
                metadata.displayName()
        );

        assertEquals(
                "1.0.0",
                metadata.version()
        );
    }

    // =========================================================
    // Basic configuration validation
    // =========================================================

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
                new LinkedHashMap<>();

        config.put(
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
                baseConfig();

        config.put(
                "operation",
                "DOCKER_RUN"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsMissingImage() {

        Map<String, Object> config =
                new LinkedHashMap<>();

        config.put(
                "operation",
                "KUBERNETES_JOB"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsBlankImage() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "image",
                "   "
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void acceptsStandardImage() {

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(
                        baseConfig()
                )
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

    // =========================================================
    // Namespace
    // =========================================================

    @Test
    void acceptsValidNamespace() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "namespace",
                "default"
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void acceptsNestedNamespaceName() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "namespace",
                "conduit-prod"
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsInvalidNamespace() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "namespace",
                "Conduit_Prod"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsNamespaceWithSlash() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "namespace",
                "conduit/prod"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // =========================================================
    // Command
    // =========================================================

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
    void rejectsNonListCommand() {

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
    void rejectsBlankCommandArgument() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "echo",
                        " "
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsControlCharactersInCommand() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "echo",
                        "hello\nworld"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // =========================================================
    // Args
    // =========================================================

    @Test
    void acceptsArgs() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "args",
                List.of(
                        "hello",
                        "world"
                )
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsNonListArgs() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "args",
                "hello"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsBlankArgs() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "args",
                List.of(
                        "hello",
                        ""
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // =========================================================
    // Environment
    // =========================================================

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
    void rejectsNullEnvironmentValue() {

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
                List.of("APP_ENV=production")
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // =========================================================
    // Resources
    // =========================================================

    @Test
    void acceptsCpuRequest() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "cpuRequest",
                "100m"
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void acceptsCpuLimit() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "cpuLimit",
                "1"
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void acceptsMemoryRequest() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "memoryRequest",
                "64Mi"
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void acceptsMemoryLimit() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "memoryLimit",
                "256Mi"
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsInvalidCpuRequest() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "cpuRequest",
                "abc"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsInvalidMemoryLimit() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "memoryLimit",
                "not-memory"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // =========================================================
    // Timeout
    // =========================================================

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

    // =========================================================
    // Log configuration
    // =========================================================

    @Test
    void acceptsValidLogLines() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "logLines",
                500
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsZeroLogLines() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "logLines",
                0
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsTooManyLogLines() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "logLines",
                10001
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    // =========================================================
    // Security / cleanup
    // =========================================================

    @Test
    void acceptsCleanupFlag() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "cleanup",
                true
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsInvalidCleanupFlag() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "cleanup",
                "true"
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void acceptsReadOnlyRootFilesystemFlag() {

        Map<String, Object> config =
                baseConfig();

        config.put(
                "readOnlyRootFilesystem",
                true
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    @Test
    void rejectsInvalidReadOnlyRootFilesystemFlag() {

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

    // =========================================================
    // Complete configuration
    // =========================================================

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
                "namespace",
                "default"
        );

        config.put(
                "command",
                List.of("echo")
        );

        config.put(
                "args",
                List.of("hello")
        );

        config.put(
                "environment",
                environment
        );

        config.put(
                "cpuRequest",
                "100m"
        );

        config.put(
                "cpuLimit",
                "500m"
        );

        config.put(
                "memoryRequest",
                "64Mi"
        );

        config.put(
                "memoryLimit",
                "256Mi"
        );

        config.put(
                "timeoutSeconds",
                300
        );

        config.put(
                "logLines",
                1000
        );

        config.put(
                "cleanup",
                true
        );

        config.put(
                "readOnlyRootFilesystem",
                true
        );

        assertDoesNotThrow(
                () -> plugin.validateConfiguration(config)
        );
    }

    // =========================================================
    // Real Kubernetes / Kind integration tests
    // =========================================================

    @Test
    void runsSimpleKubernetesJob()
            throws Exception {

        assumeKubernetesAvailable();

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "echo"
                )
        );

        config.put(
                "args",
                List.of(
                        "hello-from-conduit"
                )
        );

        PluginResult result =
                plugin.execute(
                        context(config)
                );

        assertTrue(
                result.isSuccess(),
                () -> "Kubernetes Job failed: "
                        + result.getOutput()
        );

        assertTrue(
                result.getOutput()
                        .contains(
                                "hello-from-conduit"
                        )
        );

        assertEquals(
                true,
                result.getMetadata()
                        .get("succeeded")
        );

        assertEquals(
                false,
                result.getMetadata()
                        .get("timedOut")
        );
    }

    @Test
    void passesEnvironmentVariables()
            throws Exception {

        assumeKubernetesAvailable();

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
                        "sh"
                )
        );

        config.put(
                "args",
                List.of(
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
                        .contains(
                                "production"
                        )
        );
    }

    @Test
    void handlesFailedJob()
            throws Exception {

        assumeKubernetesAvailable();

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "sh"
                )
        );

        config.put(
                "args",
                List.of(
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
                true,
                result.getMetadata()
                        .get("failed")
        );
    }

    @Test
    void collectsJobLogs()
            throws Exception {

        assumeKubernetesAvailable();

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "sh"
                )
        );

        config.put(
                "args",
                List.of(
                        "-c",
                        "echo conduit-log-test"
                )
        );

        config.put(
                "logLines",
                100
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
                        .contains(
                                "conduit-log-test"
                        )
        );
    }

    @Test
    void supportsResourceConfiguration()
            throws Exception {

        assumeKubernetesAvailable();

        Map<String, Object> config =
                baseConfig();

        config.put(
                "cpuRequest",
                "50m"
        );

        config.put(
                "cpuLimit",
                "250m"
        );

        config.put(
                "memoryRequest",
                "32Mi"
        );

        config.put(
                "memoryLimit",
                "128Mi"
        );

        config.put(
                "command",
                List.of(
                        "echo"
                )
        );

        config.put(
                "args",
                List.of(
                        "resource-test"
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
    }

    @Test
    void usesReadOnlyRootFilesystem()
            throws Exception {

        assumeKubernetesAvailable();

        Map<String, Object> config =
                baseConfig();

        config.put(
                "readOnlyRootFilesystem",
                true
        );

        config.put(
                "command",
                List.of(
                        "sh"
                )
        );

        config.put(
                "args",
                List.of(
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
    }

    @Test
    void returnsExecutionMetadata()
            throws Exception {

        assumeKubernetesAvailable();

        Map<String, Object> config =
                baseConfig();

        config.put(
                "command",
                List.of(
                        "echo"
                )
        );

        config.put(
                "args",
                List.of(
                        "metadata-test"
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

        assertEquals(
                "KUBERNETES_JOB",
                result.getMetadata()
                        .get("operation")
        );

        assertNotNull(
                result.getMetadata()
                        .get("jobName")
        );

        assertEquals(
                "default",
                result.getMetadata()
                        .get("namespace")
        );

        assertEquals(
                "alpine:3.20",
                result.getMetadata()
                        .get("image")
        );

        assertNotNull(
                result.getMetadata()
                        .get("durationMs")
        );

        assertEquals(
                true,
                result.getMetadata()
                        .get("cleanup")
        );
    }

    // =========================================================
    // Helpers
    // =========================================================

    private Map<String, Object> baseConfig() {

        Map<String, Object> config =
                new LinkedHashMap<>();

        config.put(
                "operation",
                "KUBERNETES_JOB"
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
                "kubernetes-job-test",
                30,
                configuration,
                Map.of()
        );
    }

    private void assumeKubernetesAvailable()
            throws Exception {

        Process process =
                new ProcessBuilder(
                        "kubectl",
                        "cluster-info"
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

            assumeTrue(
                    false,
                    "Kubernetes cluster check timed out"
            );

            return;
        }

        String output =
                new String(
                        process.getInputStream()
                                .readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8
                );

        assumeTrue(
                process.exitValue() == 0,
                "Kubernetes cluster is not available: "
                        + output
        );
    }
}