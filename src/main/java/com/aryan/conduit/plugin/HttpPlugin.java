package com.aryan.conduit.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aryan.conduit.plugin.PluginResult;
import com.aryan.conduit.plugin.WorkflowPlugin;
import com.aryan.conduit.workflow.entity.TaskNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class HttpPlugin implements WorkflowPlugin {

    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getType() {
        return "HTTP";
    }

    @Override
    public PluginResult execute(TaskNode task,
                                Map<String, Object> variables) {

        try {

            JsonNode config =
                    objectMapper.readTree(
                            task.getConfigurationJson());

            String url =
                    config.get("url").asText();

            String method =
                    config.has("method")
                            ? config.get("method").asText()
                            : "GET";

            HttpMethod httpMethod =
                    HttpMethod.valueOf(method);

            ResponseEntity<String> response =
                    restTemplate.exchange(
                            url,
                            httpMethod,
                            HttpEntity.EMPTY,
                            String.class);

            return PluginResult.builder()
                    .success(true)
                    .output(response.getBody())
                    .build();

        } catch (Exception ex) {

            return PluginResult.builder()
                    .success(false)
                    .output(ex.getMessage())
                    .build();
        }
    }
}