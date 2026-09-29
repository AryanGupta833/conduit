package com.aryan.conduit.plugin;

import com.aryan.conduit.execution.TaskExecutionBackend;
import com.aryan.conduit.execution.TaskExecutionResult;
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

@Component
public class ShellPlugin implements WorkflowPlugin {
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
                        "image", Map.of("type", "string"))));
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
            TaskExecutionResult result = backend.execute(image, List.of("sh", "-c", command),
                    Duration.ofSeconds(timeoutSeconds));

            if (result.timedOut()) {
                return PluginResult.builder().success(false)
                        .output(executor + " task timed out after " + timeoutSeconds + " seconds")
                        .metadata(Map.of("executionId", result.executionId(), "timedOut", true,
                                "command", command)).build();
            }
            return PluginResult.builder().success(result.isSuccess())
                    .output(result.stdout().isBlank() ? result.stderr() : result.stdout())
                    .metadata(Map.of("executionId", result.executionId(), "exitCode", result.exitCode(),
                            "command", command)).build();
        } catch (Exception ex) {
            return PluginResult.builder().success(false).output(ex.getMessage()).build();
        }
    }
}
