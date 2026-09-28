package com.aryan.conduit.plugin;

import com.aryan.conduit.workflow.entity.TaskNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;

@Component
public class ShellPlugin implements WorkflowPlugin {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                    objectMapper.readTree(task.getConfigurationJson());

            String command = config.get("command").asText();

            ProcessBuilder processBuilder;

            String os =
                    System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {

                // Windows
                processBuilder = new ProcessBuilder(
                        "cmd.exe",
                        "/c",
                        command
                );

            } else {

                // Linux / macOS
                processBuilder = new ProcessBuilder(
                        "sh",
                        "-c",
                        command
                );
            }

            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream()
                            )
                    );

            StringBuilder output =
                    new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();

            return PluginResult.builder()
                    .success(exitCode == 0)
                    .output(output.toString())
                    .metadata(
                            Map.of(
                                    "exitCode", exitCode,
                                    "command", command
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