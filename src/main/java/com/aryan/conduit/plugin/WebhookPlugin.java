package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WebhookPlugin implements WorkflowPlugin {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata(
                "WEBHOOK",
                "Webhook",
                "Send workflow data to an HTTP webhook endpoint.",
                "1.0.0",
                Map.of(
                        "type", "object",
                        "required", List.of("url"),
                        "properties", Map.of(
                                "url", Map.of(
                                        "type", "string",
                                        "format", "uri",
                                        "description", "Webhook endpoint URL"
                                ),
                                "method", Map.of(
                                        "type", "string",
                                        "enum", List.of(
                                                "GET",
                                                "POST",
                                                "PUT",
                                                "PATCH"
                                        ),
                                        "default", "POST"
                                ),
                                "headers", Map.of(
                                        "type", "object",
                                        "description", "HTTP request headers"
                                ),
                                "body", Map.of(
                                        "type", "object",
                                        "description", "Webhook request body"
                                )
                        )
                )
        );
    }

    @Override
    public void validateConfiguration(Map<String, Object> configuration)
            throws PluginConfigurationException {

        if (configuration == null) {
            throw new PluginConfigurationException(
                    "WEBHOOK configuration is required"
            );
        }

        Object urlValue = configuration.get("url");

        if (!(urlValue instanceof String url) || url.isBlank()) {
            throw new PluginConfigurationException(
                    "WEBHOOK requires a non-empty 'url'"
            );
        }

        try {
            URI uri = URI.create(url);

            String scheme = uri.getScheme();

            if (!"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme)) {

                throw new PluginConfigurationException(
                        "WEBHOOK URL must use HTTP or HTTPS"
                );
            }

        } catch (IllegalArgumentException ex) {

            throw new PluginConfigurationException(
                    "WEBHOOK has an invalid URL",
                    ex
            );
        }

        String method = String.valueOf(
                configuration.getOrDefault("method", "POST")
        ).toUpperCase();

        try {
            HttpMethod.valueOf(method);
        } catch (IllegalArgumentException ex) {
            throw new PluginConfigurationException(
                    "WEBHOOK has an unsupported HTTP method: " + method,
                    ex
            );
        }

        Object headers = configuration.get("headers");

        if (headers != null && !(headers instanceof Map<?, ?>)) {
            throw new PluginConfigurationException(
                    "WEBHOOK 'headers' must be an object"
            );
        }

        Object body = configuration.get("body");

        if (body != null
                && !(body instanceof Map<?, ?>)
                && !(body instanceof List<?>)
                && !(body instanceof String)
                && !(body instanceof Number)
                && !(body instanceof Boolean)) {

            throw new PluginConfigurationException(
                    "WEBHOOK 'body' must be a JSON-compatible value"
            );
        }
    }

    @Override
    public PluginResult execute(PluginContext context) {

        try {
            validateConfiguration(context.configuration());

            Map<String, Object> configuration =
                    context.configuration();

            String url =
                    String.valueOf(configuration.get("url"));

            String method =
                    String.valueOf(
                            configuration.getOrDefault(
                                    "method",
                                    "POST"
                            )
                    ).toUpperCase();

            HttpHeaders headers = new HttpHeaders();

            Object headersValue =
                    configuration.get("headers");

            if (headersValue instanceof Map<?, ?> configuredHeaders) {

                for (Map.Entry<?, ?> entry :
                        configuredHeaders.entrySet()) {

                    if (entry.getKey() != null
                            && entry.getValue() != null) {

                        headers.set(
                                entry.getKey().toString(),
                                entry.getValue().toString()
                        );
                    }
                }
            }

            Object bodyValue =
                    configuration.get("body");

            String body = null;

            if (bodyValue != null) {
                if (bodyValue instanceof String string) {
                    body = string;
                } else {
                    body = objectMapper.writeValueAsString(bodyValue);
                }
            }

            HttpEntity<String> requestEntity =
                    new HttpEntity<>(body, headers);

            ResponseEntity<String> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.valueOf(method),
                            requestEntity,
                            String.class
                    );

            boolean success =
                    response.getStatusCode().is2xxSuccessful();

            return PluginResult.builder()
                    .success(success)
                    .output(response.getBody())
                    .metadata(Map.of(
                            "statusCode",
                            response.getStatusCode().value(),
                            "method",
                            method,
                            "url",
                            url
                    ))
                    .build();

        } catch (Exception ex) {

            return PluginResult.builder()
                    .success(false)
                    .output(
                            ex.getMessage() == null
                                    ? ex.getClass().getSimpleName()
                                    : ex.getMessage()
                    )
                    .build();
        }
    }
}