package com.aryan.conduit.plugin;

import com.aryan.conduit.execution.TaskExecutionBackend;
import com.aryan.conduit.execution.TaskExecutionResult;
import com.aryan.conduit.execution.workspace.ExecutionWorkspace;
import com.aryan.conduit.docker.ManagedWorkspaceMount;
import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ShellPlugin implements WorkflowPlugin {
    private static final Pattern CONTAINER_PATH = Pattern.compile("^/[A-Za-z0-9._/-]*$");
    private final TaskExecutionBackend dockerExecutionService;
    private final TaskExecutionBackend kubernetesExecutionService;

    public ShellPlugin(@Qualifier("dockerExecutionService") TaskExecutionBackend dockerExecutionService,
                       @Qualifier("kubernetesExecutionService") TaskExecutionBackend kubernetesExecutionService) {
        this.dockerExecutionService = dockerExecutionService;
        this.kubernetesExecutionService = kubernetesExecutionService;
    }

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata("SHELL", "Shell Command", "Run a shell command with Docker or Kubernetes.", "1.0.0",
                Map.of("type", "object", "required", List.of("command"), "properties", Map.of(
                        "command", Map.of("type", "string"), "executor", Map.of("type", "string"),
                        "image", Map.of("type", "string"), "structuredOutput", Map.of("type", "boolean", "default", true),
                        "cpus", Map.of("type", "number", "minimum", 0.1, "maximum", 8),
                        "memoryMb", Map.of("type", "integer", "minimum", 64, "maximum", 8192),
                        "pidsLimit", Map.of("type", "integer", "minimum", 16, "maximum", 4096),
                        "networkDisabled", Map.of("type", "boolean", "default", false))));
    }

    @Override
    public void validateConfiguration(Map<String, Object> configuration) {
        Object command = configuration.get("command");
        if (command == null || command.toString().isBlank()) {
            throw new PluginConfigurationException("Missing 'command' in SHELL configuration");
        }
        String executor = String.valueOf(configuration.getOrDefault("executor", "DOCKER"));
        if (!executor.equalsIgnoreCase("DOCKER") && !executor.equalsIgnoreCase("KUBERNETES")) {
            throw new PluginConfigurationException("Unsupported SHELL executor: " + executor);
        }
        Object workingDirectory = configuration.get("workingDirectory");
        if (workingDirectory != null && (!(workingDirectory instanceof String path) || !CONTAINER_PATH.matcher(path).matches()))
            throw new PluginConfigurationException("SHELL 'workingDirectory' must be a path inside the container");
        Object workspaceMode = configuration.get("workspaceMode");
        if (workspaceMode != null && !List.of("READ_ONLY", "READ_WRITE").contains(String.valueOf(workspaceMode).toUpperCase()))
            throw new PluginConfigurationException("SHELL 'workspaceMode' must be READ_ONLY or READ_WRITE");
        if (configuration.get("structuredOutput") != null && !(configuration.get("structuredOutput") instanceof Boolean))
            throw new PluginConfigurationException("SHELL 'structuredOutput' must be boolean");
        Object cpus = configuration.get("cpus");
        if (cpus != null && (!(cpus instanceof Number n) || n.doubleValue() < 0.1 || n.doubleValue() > 8)) throw new PluginConfigurationException("SHELL cpus must be between 0.1 and 8");
        Object memory = configuration.get("memoryMb");
        if (memory != null && (!(memory instanceof Number n) || n.longValue() < 64 || n.longValue() > 8192)) throw new PluginConfigurationException("SHELL memoryMb must be between 64 and 8192");
        Object pids = configuration.get("pidsLimit");
        if (pids != null && (!(pids instanceof Number n) || n.longValue() < 16 || n.longValue() > 4096)) throw new PluginConfigurationException("SHELL pidsLimit must be between 16 and 4096");
        if (configuration.get("networkDisabled") != null && !(configuration.get("networkDisabled") instanceof Boolean)) throw new PluginConfigurationException("SHELL networkDisabled must be boolean");
    }

    @Override
    public PluginResult execute(PluginContext context) {
        try {
            Map<String, Object> config = context.configuration();
            String command = String.valueOf(config.get("command"));
            String executor = String.valueOf(config.getOrDefault("executor", "DOCKER"));
            String image = String.valueOf(config.getOrDefault("image", "alpine:latest"));
            TaskExecutionBackend backend = executor.equalsIgnoreCase("KUBERNETES")
                    ? kubernetesExecutionService : dockerExecutionService;
            int timeoutSeconds = context.timeoutSeconds() != null && context.timeoutSeconds() > 0
                    ? context.timeoutSeconds() : 30;
            String workingDirectory = String.valueOf(config.getOrDefault("workingDirectory", ManagedWorkspaceMount.CONTAINER_PATH));
            boolean readOnly = "READ_ONLY".equalsIgnoreCase(String.valueOf(config.getOrDefault("workspaceMode", "READ_WRITE")));
            ExecutionWorkspace workspace = null;
            if (context.workspace() instanceof ExecutionWorkspace executionWorkspace) {
                workspace = executionWorkspace;
            } else if (context.workspace() != null) {
                throw new PluginConfigurationException("SHELL requires Conduit's managed execution workspace");
            }
            String effectiveCommand = workspace == null ? command : "cd '" + workingDirectory + "' && " + command;
            TaskExecutionResult result;
            if (executor.equalsIgnoreCase("DOCKER")) {
                if (!(dockerExecutionService instanceof com.aryan.conduit.docker.DockerExecutionService docker))
                    throw new PluginConfigurationException("Docker backend does not support configured SHELL isolation controls");
                result = docker.executeScript(image, effectiveCommand, Duration.ofSeconds(timeoutSeconds), workspace, readOnly,
                        config.get("cpus") instanceof Number n ? n.doubleValue() : 1.0,
                        config.get("memoryMb") instanceof Number n ? n.longValue() : 256,
                        config.get("pidsLimit") instanceof Number n ? n.longValue() : 256,
                        Boolean.TRUE.equals(config.get("networkDisabled")));
            } else {
                if (config.containsKey("cpus") || config.containsKey("memoryMb") || config.containsKey("pidsLimit") || Boolean.TRUE.equals(config.get("networkDisabled")))
                    throw new PluginConfigurationException("SHELL isolation controls are currently supported only by the Docker executor");
                result = workspace == null
                        ? backend.execute(image, List.of("sh", "-c", effectiveCommand), Duration.ofSeconds(timeoutSeconds))
                        : backend.execute(image, List.of("sh", "-c", effectiveCommand), Duration.ofSeconds(timeoutSeconds), workspace, readOnly);
            }

            if (result.timedOut()) {
                PluginResult.Builder timedOut = PluginResult.builder().success(false)
                        .output(executor + " task timed out after " + timeoutSeconds + " seconds")
                        .metadata(Map.of("executionId", result.executionId(), "timedOut", true,
                                "operation", "SHELL", "command", command));
                if (!Boolean.FALSE.equals(config.get("structuredOutput"))) timedOut.variables(Map.of("stdout", result.stdout(), "stderr", result.stderr(), "exitCode", result.exitCode(), "timedOut", true));
                return timedOut.build();
            }
            PluginResult.Builder response = PluginResult.builder().success(result.isSuccess())
                    .output(result.stdout().isBlank() ? result.stderr() : result.stdout())
                    .metadata(Map.of("operation", "SHELL", "executionId", result.executionId(), "exitCode", result.exitCode(),
                            "outputTruncated", result.outputTruncated(), "command", command));
            if (!Boolean.FALSE.equals(config.get("structuredOutput")))
                response.variables(Map.of("stdout", result.stdout(), "stderr", result.stderr(), "exitCode", result.exitCode(), "timedOut", false));
            return response.build();
        } catch (Exception ex) {
            return PluginResult.builder().success(false).output(ex.getMessage()).build();
        }
    }
}
