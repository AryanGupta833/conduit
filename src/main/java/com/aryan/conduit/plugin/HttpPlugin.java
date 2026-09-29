package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class HttpPlugin implements WorkflowPlugin {

    private final RestTemplate restTemplate;

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata("HTTP", "HTTP Request", "Execute an HTTP request.", "1.0.0",
                Map.of("type", "object", "required", List.of("url"), "properties", Map.of(
                        "url", Map.of("type", "string"), "method", Map.of("type", "string"))));
    }

    @Override
    public void validateConfiguration(Map<String, Object> configuration) {
        Object url = configuration.get("url");
        if (url == null || url.toString().isBlank()) {
            throw new PluginConfigurationException("HTTP plugin requires a non-empty 'url' configuration");
        }
        try {
            HttpMethod.valueOf(String.valueOf(configuration.getOrDefault("method", "GET")).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new PluginConfigurationException("HTTP plugin has an unsupported 'method'", e);
        }
    }

    @Override
    public PluginResult execute(PluginContext context) {

        try {
            String url = String.valueOf(context.configuration().get("url"));
            String method = String.valueOf(context.configuration().getOrDefault("method", "GET")).toUpperCase();

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
