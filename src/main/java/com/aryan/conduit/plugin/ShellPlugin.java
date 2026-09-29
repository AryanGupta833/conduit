package com.aryan.conduit.plugin;

import com.aryan.conduit.docker.DockerExecutionResult;
import com.aryan.conduit.docker.DockerExecutionService;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ShellPlugin implements WorkflowPlugin {

    private final DockerExecutionService dockerExecutionService;

    private final ObjectMapper objectMapper =
            new ObjectMapper();

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

            int timeoutSeconds =
                    task.getTimeoutSeconds() != null
                            && task.getTimeoutSeconds() > 0
                            ? task.getTimeoutSeconds()
                            : 30;

            DockerExecutionResult result =
                    dockerExecutionService.execute(
                            "alpine:latest",
                            List.of(
                                    "sh",
                                    "-c",
                                    command
                            ),
                            Duration.ofSeconds(
                                    timeoutSeconds
                            )
                    );

            if (result.timedOut()) {

                return PluginResult.builder()
                        .success(false)
                        .output(
                                "Docker task timed out after "
                                        + timeoutSeconds
                                        + " seconds"
                        )
                        .metadata(
                                Map.of(
                                        "containerId",
                                        result.containerId(),
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
                                    "containerId",
                                    result.containerId(),
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
                    .output(
                            ex.getMessage()
                    )
                    .build();
        }
    }
}