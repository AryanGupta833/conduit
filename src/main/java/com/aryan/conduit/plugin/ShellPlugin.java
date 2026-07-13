package com.aryan.conduit.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aryan.conduit.plugin.PluginResult;
import com.aryan.conduit.plugin.WorkflowPlugin;
import com.aryan.conduit.workflow.entity.TaskNode;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;

@Component
public class ShellPlugin implements WorkflowPlugin {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    @Override
    public String getType() {
        return "SHELL";
    }

    @Override
    public PluginResult execute(TaskNode task,
                                Map<String, Object> variables) {

        try {

            JsonNode config =
                    objectMapper.readTree(
                            task.getConfigurationJson());

            String command =
                    config.get("command").asText();

            Process process =
                    Runtime.getRuntime()
                            .exec(command);

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream()));

            StringBuilder output =
                    new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line)
                        .append("\n");
            }

            process.waitFor();

            return PluginResult.builder()
                    .success(true)
                    .output(output.toString())
                    .build();

        } catch (Exception ex) {

            return PluginResult.builder()
                    .success(false)
                    .output(ex.getMessage())
                    .build();
        }
    }
}