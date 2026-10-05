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
import com.aryan.conduit.plugin.security.SsrfAddressPolicy;
import java.net.URI;

import java.util.List;
import java.util.Map;
import org.springframework.http.client.ClientHttpRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class HttpPlugin implements WorkflowPlugin {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata("HTTP", "HTTP Request", "Execute an HTTP request.", "1.0.0",
                Map.of("type", "object", "required", List.of("url"), "properties", Map.of(
                        "url", Map.of("type", "string"), "method", Map.of("type", "string"),
                        "headers", Map.of("type", "object"), "body", Map.of("description", "JSON-compatible request body"),
                        "allowPrivateNetwork", Map.of("type", "boolean", "default", false))));
    }

    @Override
    public void validateConfiguration(Map<String, Object> configuration) {
        Object url = configuration.get("url");
        if (url == null || url.toString().isBlank()) {
            throw new PluginConfigurationException("HTTP plugin requires a non-empty 'url' configuration");
        }
        try {
            HttpMethod.valueOf(String.valueOf(configuration.getOrDefault("method", "GET")).toUpperCase());
            if (configuration.get("allowPrivateNetwork") != null && !(configuration.get("allowPrivateNetwork") instanceof Boolean))
                throw new PluginConfigurationException("HTTP 'allowPrivateNetwork' must be a boolean");
            if (configuration.get("headers") != null && !(configuration.get("headers") instanceof Map<?, ?>))
                throw new PluginConfigurationException("HTTP 'headers' must be an object");
            if (configuration.get("headers") instanceof Map<?, ?> headers) {
                if (headers.size() > 50) throw new PluginConfigurationException("HTTP accepts at most 50 request headers");
                headers.forEach((key, value) -> {
                    if (!(key instanceof String name) || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}")
                            || List.of("host", "content-length", "transfer-encoding", "connection", "proxy-authorization").contains(name.toLowerCase(java.util.Locale.ROOT)))
                        throw new PluginConfigurationException("HTTP contains a restricted or invalid request header");
                    if (value != null && String.valueOf(value).length() > 8192)
                        throw new PluginConfigurationException("HTTP request header value is too long");
                });
            }
            if (configuration.get("body") != null) {
                try {
                    if (objectMapper.writeValueAsBytes(configuration.get("body")).length > 256 * 1024)
                        throw new PluginConfigurationException("HTTP request body exceeds 256 KiB");
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new PluginConfigurationException("HTTP request body must be JSON-compatible", e);
                }
            }
            SsrfAddressPolicy.validate(URI.create(url.toString()), Boolean.TRUE.equals(configuration.get("allowPrivateNetwork")));
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

            SsrfAddressPolicy.validate(URI.create(url), Boolean.TRUE.equals(context.configuration().get("allowPrivateNetwork")));
            byte[] requestBody = context.configuration().get("body") == null ? null
                    : objectMapper.writeValueAsBytes(context.configuration().get("body"));
            BoundedHttpResponse response = restTemplate.execute(URI.create(url), httpMethod,
                    request -> writeRequest(context, request, requestBody), BoundedHttpResponse::read);
            if (response == null) throw new IllegalStateException("HTTP request returned no response");

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                int status = response.statusCode();
                throw new PluginHttpFailureException(status, status == 408 || status == 425 || status == 429 || status >= 500,
                        parseRetryAfter(response.retryAfter()));
            }

            return PluginResult.builder()
                    .success(response.statusCode() >= 200 && response.statusCode() < 300)
                    .output(response.body())
                    .metadata(Map.of("statusCode", response.statusCode(), "outputTruncated", response.truncated()))
                    .build();

        } catch (Exception ex) {

            if (ex instanceof PluginHttpFailureException retryFailure) throw retryFailure;

            return PluginResult.builder()
                    .success(false)
                    .output("HTTP request failed: " + ex.getClass().getSimpleName())
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

    private void writeRequest(PluginContext context, ClientHttpRequest request, byte[] body) throws java.io.IOException {
        Object configured = context.configuration().get("headers");
        if (configured instanceof Map<?, ?> headers) headers.forEach((key, value) -> {
            if (key != null && value != null) request.getHeaders().set(key.toString(), value.toString());
        });
        if (context.idempotencyKey() != null && !context.idempotencyKey().isBlank()
                && !request.getHeaders().containsKey("Idempotency-Key"))
            request.getHeaders().set("Idempotency-Key", context.idempotencyKey());
        if (body != null) {
            if (!request.getHeaders().containsKey(HttpHeaders.CONTENT_TYPE))
                request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.getBody().write(body);
        }
    }
}
