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
import com.aryan.conduit.plugin.security.SsrfAddressPolicy;

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
                                ),
                                "allowPrivateNetwork", Map.of(
                                        "type", "boolean", "default", false,
                                        "description", "Allow local/private destinations for trusted workflows"
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
            if (configuration.get("allowPrivateNetwork") != null && !(configuration.get("allowPrivateNetwork") instanceof Boolean))
                throw new PluginConfigurationException("WEBHOOK 'allowPrivateNetwork' must be a boolean");
            SsrfAddressPolicy.validate(uri, Boolean.TRUE.equals(configuration.get("allowPrivateNetwork")));

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

            SsrfAddressPolicy.validate(URI.create(url), Boolean.TRUE.equals(configuration.get("allowPrivateNetwork")));

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
            if (body != null && body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
                throw new PluginConfigurationException("WEBHOOK request body exceeds the 1 MiB limit");

            HttpEntity<String> requestEntity =
                    new HttpEntity<>(body, headers);

            final String requestBody = body;
            BoundedHttpResponse response = restTemplate.execute(URI.create(url), HttpMethod.valueOf(method), request -> {
                request.getHeaders().putAll(headers);
                if (context.idempotencyKey() != null && !context.idempotencyKey().isBlank()
                        && !request.getHeaders().containsKey("Idempotency-Key"))
                    request.getHeaders().set("Idempotency-Key", context.idempotencyKey());
                if (requestBody != null) request.getBody().write(requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }, BoundedHttpResponse::read);
            if (response == null) throw new IllegalStateException("Webhook returned no response");

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;

            if (!success) {
                int status = response.statusCode();
                throw new PluginHttpFailureException(status, status == 408 || status == 425 || status == 429 || status >= 500,
                        parseRetryAfter(response.retryAfter()));
            }

            return PluginResult.builder()
                    .success(success)
                    .output(response.body())
                    .metadata(Map.of(
                            "statusCode", response.statusCode(),
                            "outputTruncated", response.truncated(),
                            "method",
                            method,
                            "host", URI.create(url).getHost()
                    ))
                    .build();

        } catch (Exception ex) {

            if (ex instanceof PluginHttpFailureException retryFailure) throw retryFailure;

            return PluginResult.builder()
                    .success(false)
                    .output("Webhook request failed: " + ex.getClass().getSimpleName())
                    .build();
        }
    }

    private long parseRetryAfter(String value) {
        if (value == null) return 0;
        try { return Math.max(0, Long.parseLong(value.trim()) * 1000); }
        catch (NumberFormatException ignored) {
            try { return Math.max(0, java.time.Duration.between(java.time.Instant.now(),
                    java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); }
            catch (Exception ignoredAgain) { return 0; }
        }
    }
}
