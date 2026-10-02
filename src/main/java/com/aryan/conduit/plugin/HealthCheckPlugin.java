package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HealthCheckPlugin implements WorkflowPlugin {

    private static final int DEFAULT_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_EXPECTED_STATUS = 200;

    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 300;

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /*
     * Health checks do not need the complete response body.
     * Keep the captured body bounded to avoid excessive memory usage.
     */
    private static final int MAX_RESPONSE_BODY_CHARS = 4096;

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of(
                "HEALTH_CHECK",
                "Health Check",
                "Checks HTTP/HTTPS endpoints or TCP connectivity",
                "1.0.0"
        );
    }

    @Override
    public void validateConfiguration(
            Map<String, Object> configuration)
            throws PluginConfigurationException {

        if (configuration == null) {
            throw new PluginConfigurationException(
                    "HEALTH_CHECK configuration is required"
            );
        }

        String type = requireString(
                configuration,
                "type"
        ).toUpperCase();

        switch (type) {
            case "HTTP" -> validateHttp(configuration);
            case "TCP" -> validateTcp(configuration);
            default -> throw new PluginConfigurationException(
                    "HEALTH_CHECK 'type' must be HTTP or TCP"
            );
        }
    }

    @Override
    public PluginResult execute(PluginContext context) {

        Map<String, Object> configuration =
                context.configuration();

        validateConfiguration(configuration);

        String type = requireString(
                configuration,
                "type"
        ).toUpperCase();

        try {
            return switch (type) {
                case "HTTP" -> executeHttp(configuration);
                case "TCP" -> executeTcp(configuration);
                default -> throw new PluginConfigurationException(
                        "HEALTH_CHECK 'type' must be HTTP or TCP"
                );
            };

        } catch (PluginConfigurationException ex) {
            throw ex;

        } catch (Exception ex) {
            return failureResult(
                    type,
                    "Health check failed: "
                            + errorMessage(ex)
            );
        }
    }

    private PluginResult executeHttp(
            Map<String, Object> configuration)
            throws IOException, InterruptedException {

        String url = requireString(
                configuration,
                "url"
        );

        int timeoutSeconds = getInt(
                configuration,
                "timeoutSeconds",
                DEFAULT_TIMEOUT_SECONDS
        );

        int expectedStatus = getInt(
                configuration,
                "expectedStatus",
                DEFAULT_EXPECTED_STATUS
        );

        URI uri;

        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw new PluginConfigurationException(
                    "HEALTH_CHECK 'url' is invalid"
            );
        }

        validateHttpUri(uri);

        /*
         * Create the client with the same timeout configured for this
         * particular health check. This makes connection timeout and
         * request timeout consistent.
         */
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(
                        Duration.ofSeconds(timeoutSeconds)
                )
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();

        Instant start = Instant.now();

        HttpResponse<String> response;

        try {
            response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        }

        long latencyMs = Duration.between(
                start,
                Instant.now()
        ).toMillis();

        int statusCode = response.statusCode();

        boolean healthy = statusCode == expectedStatus;

        Map<String, Object> metadata =
                new LinkedHashMap<>();

        metadata.put(
                "status",
                healthy ? "HEALTHY" : "UNHEALTHY"
        );

        metadata.put(
                "checkType",
                "HTTP"
        );

        metadata.put(
                "url",
                url
        );

        metadata.put(
                "statusCode",
                statusCode
        );

        metadata.put(
                "expectedStatus",
                expectedStatus
        );

        metadata.put(
                "latencyMs",
                latencyMs
        );

        String body = response.body();

        if (body != null && !body.isBlank()) {
            metadata.put(
                    "responseBody",
                    truncateBody(body)
            );
        }

        if (healthy) {
            return PluginResult.builder()
                    .success(true)
                    .output(
                            "HTTP health check passed with status "
                                    + statusCode
                    )
                    .metadata(metadata)
                    .build();
        }

        return PluginResult.builder()
                .success(false)
                .output(
                        "HTTP health check failed: expected status "
                                + expectedStatus
                                + " but received "
                                + statusCode
                )
                .metadata(metadata)
                .build();
    }

    private PluginResult executeTcp(
            Map<String, Object> configuration) {

        String host = requireString(
                configuration,
                "host"
        );

        int port = getInt(
                configuration,
                "port",
                -1
        );

        int timeoutSeconds = getInt(
                configuration,
                "timeoutSeconds",
                DEFAULT_TIMEOUT_SECONDS
        );

        long start = System.nanoTime();

        try {
            InetSocketAddress address =
                    new InetSocketAddress(host, port);

            try (var socket = new java.net.Socket()) {

                socket.connect(
                        address,
                        timeoutSeconds * 1000
                );
            }

            long latencyMs = elapsedMillis(start);

            Map<String, Object> metadata =
                    new LinkedHashMap<>();

            metadata.put(
                    "status",
                    "HEALTHY"
            );

            metadata.put(
                    "checkType",
                    "TCP"
            );

            metadata.put(
                    "host",
                    host
            );

            metadata.put(
                    "port",
                    port
            );

            metadata.put(
                    "latencyMs",
                    latencyMs
            );

            return PluginResult.builder()
                    .success(true)
                    .output(
                            "TCP health check passed"
                    )
                    .metadata(metadata)
                    .build();

        } catch (IOException ex) {

            long latencyMs = elapsedMillis(start);

            Map<String, Object> metadata =
                    new LinkedHashMap<>();

            metadata.put(
                    "status",
                    "UNHEALTHY"
            );

            metadata.put(
                    "checkType",
                    "TCP"
            );

            metadata.put(
                    "host",
                    host
            );

            metadata.put(
                    "port",
                    port
            );

            metadata.put(
                    "latencyMs",
                    latencyMs
            );

            return PluginResult.builder()
                    .success(false)
                    .output(
                            "TCP health check failed: "
                                    + errorMessage(ex)
                    )
                    .metadata(metadata)
                    .build();
        }
    }

    private void validateHttp(
            Map<String, Object> configuration) {

        String url = requireString(
                configuration,
                "url"
        );

        URI uri;

        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw new PluginConfigurationException(
                    "HEALTH_CHECK 'url' is invalid"
            );
        }

        validateHttpUri(uri);

        validateTimeout(configuration);

        int expectedStatus = getInt(
                configuration,
                "expectedStatus",
                DEFAULT_EXPECTED_STATUS
        );

        if (expectedStatus < 100
                || expectedStatus > 599) {

            throw new PluginConfigurationException(
                    "HEALTH_CHECK 'expectedStatus' must be "
                            + "between 100 and 599"
            );
        }
    }

    private void validateTcp(
            Map<String, Object> configuration) {

        String host = requireString(
                configuration,
                "host"
        );

        /*
         * Host must be a hostname or IP address, not a URL.
         */
        if (host.contains(" ")
                || host.contains("/")
                || host.contains("\\")
                || host.contains(":")) {

            throw new PluginConfigurationException(
                    "HEALTH_CHECK 'host' is invalid"
            );
        }

        int port = getInt(
                configuration,
                "port",
                -1
        );

        if (port < MIN_PORT
                || port > MAX_PORT) {

            throw new PluginConfigurationException(
                    "HEALTH_CHECK 'port' must be between "
                            + MIN_PORT
                            + " and "
                            + MAX_PORT
            );
        }

        validateTimeout(configuration);
    }

    private void validateTimeout(
            Map<String, Object> configuration) {

        int timeoutSeconds = getInt(
                configuration,
                "timeoutSeconds",
                DEFAULT_TIMEOUT_SECONDS
        );

        if (timeoutSeconds < MIN_TIMEOUT_SECONDS
                || timeoutSeconds > MAX_TIMEOUT_SECONDS) {

            throw new PluginConfigurationException(
                    "HEALTH_CHECK 'timeoutSeconds' must be between "
                            + MIN_TIMEOUT_SECONDS
                            + " and "
                            + MAX_TIMEOUT_SECONDS
            );
        }
    }

    private void validateHttpUri(URI uri) {

        String scheme = uri.getScheme();

        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme))) {

            throw new PluginConfigurationException(
                    "HEALTH_CHECK 'url' must use HTTP or HTTPS"
            );
        }

        if (uri.getHost() == null
                || uri.getHost().isBlank()) {

            throw new PluginConfigurationException(
                    "HEALTH_CHECK 'url' must contain a valid host"
            );
        }

        /*
         * Credentials must not be embedded in workflow configuration.
         */
        if (uri.getUserInfo() != null) {
            throw new PluginConfigurationException(
                    "HEALTH_CHECK 'url' must not contain credentials"
            );
        }
    }

    private String requireString(
            Map<String, Object> configuration,
            String key) {

        Object value = configuration.get(key);

        if (value == null
                || value.toString().isBlank()) {

            throw new PluginConfigurationException(
                    "HEALTH_CHECK '" + key + "' is required"
            );
        }

        return value.toString().trim();
    }

    private int getInt(
            Map<String, Object> configuration,
            String key,
            int defaultValue) {

        Object value = configuration.get(key);

        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String string) {
            try {
                return Integer.parseInt(
                        string.trim()
                );
            } catch (NumberFormatException ex) {
                throw new PluginConfigurationException(
                        "HEALTH_CHECK '"
                                + key
                                + "' must be an integer"
                );
            }
        }

        throw new PluginConfigurationException(
                "HEALTH_CHECK '"
                        + key
                        + "' must be an integer"
        );
    }

    private String truncateBody(String body) {

        if (body.length()
                <= MAX_RESPONSE_BODY_CHARS) {

            return body;
        }

        return body.substring(
                0,
                MAX_RESPONSE_BODY_CHARS
        ) + "...";
    }

    private long elapsedMillis(long startNanos) {

        return Duration.ofNanos(
                System.nanoTime() - startNanos
        ).toMillis();
    }

    private PluginResult failureResult(
            String checkType,
            String message) {

        return PluginResult.builder()
                .success(false)
                .output(message)
                .metadata(Map.of(
                        "status",
                        "UNHEALTHY",
                        "checkType",
                        checkType
                ))
                .build();
    }

    private String errorMessage(Exception ex) {

        if (ex.getMessage() != null
                && !ex.getMessage().isBlank()) {

            return ex.getMessage();
        }

        return ex.getClass().getSimpleName();
    }
}