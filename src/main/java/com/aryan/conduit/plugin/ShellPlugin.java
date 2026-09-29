package com.aryan.conduit.plugin;

import com.aryan.conduit.execution.TaskExecutionBackend;
import com.aryan.conduit.execution.TaskExecutionResult;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class ShellPlugin implements WorkflowPlugin {

    @Qualifier("dockerExecutionService")
    private final TaskExecutionBackend dockerExecutionService;

    @Qualifier("kubernetesExecutionService")
    private final TaskExecutionBackend kubernetesExecutionService;

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    public ShellPlugin(
            @Qualifier("dockerExecutionService") TaskExecutionBackend dockerExecutionService,
            @Qualifier("kubernetesExecutionService") TaskExecutionBackend kubernetesExecutionService) {
        this.dockerExecutionService = dockerExecutionService;
        this.kubernetesExecutionService = kubernetesExecutionService;
    }

    @Override
    public String getType() {
        return "SHELL";
    }

    @Override
    public PluginResult execute(
            TaskNode task,
            Map<String, Object> variables
    ) {

        try {

            JsonNode config =
                    objectMapper.readTree(
                            task.getConfigurationJson()
                    );

            if (!config.has("command")) {
                return PluginResult.builder()
                        .success(false)
                        .output(
                                "Missing 'command' in SHELL configuration"
                        )
                        .build();
            }

            String command =
                    config.get("command").asText();

            String executor = config.path("executor").asText("DOCKER");
            String image = config.path("image").asText("alpine:latest");
            TaskExecutionBackend backend = switch (executor.toUpperCase()) {
                case "DOCKER" -> dockerExecutionService;
                case "KUBERNETES" -> kubernetesExecutionService;
                default -> throw new IllegalArgumentException("Unsupported SHELL executor: " + executor);
            };

            int timeoutSeconds =
                    task.getTimeoutSeconds() != null
                            && task.getTimeoutSeconds() > 0
                            ? task.getTimeoutSeconds()
                            : 30;

            TaskExecutionResult result =
                    backend.execute(
                            image,
                            List.of(
                                    "sh",
                                    "-c",
                                    command
                            ),
                            Duration.ofSeconds(timeoutSeconds)
                    );

            if (result.timedOut()) {

                return PluginResult.builder()
                        .success(false)
                        .output(
                                executor + " task timed out after "
                                        + timeoutSeconds
                                        + " seconds"
                        )
                        .metadata(
                                Map.of(
                                        "executionId",
                                        result.executionId(),
                                        "timedOut",
                                        true,
                                        "command",
                                        command
                                )
                        )
                        .build();
            }

            return PluginResult.builder()
                    .success(result.isSuccess())
                    .output(
                            result.stdout().isBlank()
                                    ? result.stderr()
                                    : result.stdout()
                    )
                    .metadata(
                            Map.of(
                                    "executionId",
                                    result.executionId(),
                                    "exitCode",
                                    result.exitCode(),
                                    "command",
                                    command
                            )
                    )
                    .build();

        } catch (Exception ex) {

            return PluginResult.builder()
                    .success(false)
                    .output(ex.getMessage())
                    .build();
        }
    }
}
