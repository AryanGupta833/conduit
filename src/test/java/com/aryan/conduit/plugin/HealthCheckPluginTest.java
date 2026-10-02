package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckPluginTest {

    private HealthCheckPlugin plugin;
    private HttpServer httpServer;

    @BeforeEach
    void setUp() {
        plugin = new HealthCheckPlugin();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    void exposesCorrectMetadata() {

        var metadata = plugin.metadata();

        assertEquals(
                "HEALTH_CHECK",
                metadata.type()
        );

        assertEquals(
                "Health Check",
                metadata.displayName()
        );

        assertEquals(
                "1.0.0",
                metadata.version()
        );
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    @Test
    void httpHealthCheckSucceeds() throws Exception {

        startHttpServer(
                200,
                "healthy"
        );

        PluginContext context = httpContext(
                serverUrl(),
                Map.of(
                        "timeoutSeconds", 5,
                        "expectedStatus", 200
                )
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertTrue(
                result.getOutput().contains(
                        "HTTP health check passed"
                )
        );

        assertEquals(
                "HEALTHY",
                result.getMetadata().get("status")
        );

        assertEquals(
                "HTTP",
                result.getMetadata().get("checkType")
        );

        assertEquals(
                200,
                result.getMetadata().get("statusCode")
        );

        assertEquals(
                200,
                result.getMetadata().get("expectedStatus")
        );

        assertEquals(
                "healthy",
                result.getMetadata().get("responseBody")
        );

        assertNotNull(
                result.getMetadata().get("latencyMs")
        );
    }

    @Test
    void httpHealthCheckFailsForUnexpectedStatus()
            throws Exception {

        startHttpServer(
                503,
                "service unavailable"
        );

        PluginContext context = httpContext(
                serverUrl(),
                Map.of(
                        "timeoutSeconds", 5,
                        "expectedStatus", 200
                )
        );

        var result = plugin.execute(context);

        assertFalse(result.isSuccess());

        assertEquals(
                "UNHEALTHY",
                result.getMetadata().get("status")
        );

        assertEquals(
                "HTTP",
                result.getMetadata().get("checkType")
        );

        assertEquals(
                503,
                result.getMetadata().get("statusCode")
        );

        assertEquals(
                200,
                result.getMetadata().get("expectedStatus")
        );

        assertTrue(
                result.getOutput().contains(
                        "expected status 200"
                )
        );
    }

    @Test
    void acceptsCustomExpectedStatus()
            throws Exception {

        startHttpServer(
                201,
                "created"
        );

        PluginContext context = httpContext(
                serverUrl(),
                Map.of(
                        "expectedStatus", 201
                )
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertEquals(
                201,
                result.getMetadata().get("statusCode")
        );

        assertEquals(
                201,
                result.getMetadata().get("expectedStatus")
        );
    }

    @Test
    void usesDefaultExpectedStatus200()
            throws Exception {

        startHttpServer(
                200,
                "ok"
        );

        PluginContext context = httpContext(
                serverUrl(),
                Map.of()
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertEquals(
                200,
                result.getMetadata().get("expectedStatus")
        );
    }

    @Test
    void truncatesLargeResponseBody()
            throws Exception {

        String largeBody = "x".repeat(10_000);

        startHttpServer(
                200,
                largeBody
        );

        PluginContext context = httpContext(
                serverUrl(),
                Map.of()
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        String responseBody =
                (String) result.getMetadata()
                        .get("responseBody");

        assertNotNull(responseBody);

        assertEquals(
                4099,
                responseBody.length()
        );

        assertTrue(
                responseBody.endsWith("...")
        );
    }

    @Test
    void followsHttpRedirect()
            throws Exception {

        startRedirectServer();

        PluginContext context = httpContext(
                serverUrl() + "/redirect",
                Map.of()
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertEquals(
                200,
                result.getMetadata().get("statusCode")
        );

        assertEquals(
                "redirected",
                result.getMetadata().get("responseBody")
        );
    }

    // -------------------------------------------------------------------------
    // TCP
    // -------------------------------------------------------------------------

    @Test
    void tcpHealthCheckSucceeds()
            throws Exception {

        try (var serverSocket =
                     new java.net.ServerSocket(0)) {

            int port = serverSocket.getLocalPort();

            PluginContext context = tcpContext(
                    "127.0.0.1",
                    port,
                    Map.of(
                            "timeoutSeconds", 2
                    )
            );

            var result = plugin.execute(context);

            assertTrue(result.isSuccess());

            assertEquals(
                    "HEALTHY",
                    result.getMetadata().get("status")
            );

            assertEquals(
                    "TCP",
                    result.getMetadata().get("checkType")
            );

            assertEquals(
                    "127.0.0.1",
                    result.getMetadata().get("host")
            );

            assertEquals(
                    port,
                    result.getMetadata().get("port")
            );

            assertNotNull(
                    result.getMetadata().get("latencyMs")
            );
        }
    }

    @Test
    void tcpHealthCheckFailsWhenPortUnavailable()
            throws Exception {

        int port;

        try (var serverSocket =
                     new java.net.ServerSocket(0)) {

            port = serverSocket.getLocalPort();
        }

        PluginContext context = tcpContext(
                "127.0.0.1",
                port,
                Map.of(
                        "timeoutSeconds", 1
                )
        );

        var result = plugin.execute(context);

        assertFalse(result.isSuccess());

        assertEquals(
                "UNHEALTHY",
                result.getMetadata().get("status")
        );

        assertEquals(
                "TCP",
                result.getMetadata().get("checkType")
        );

        assertEquals(
                "127.0.0.1",
                result.getMetadata().get("host")
        );

        assertEquals(
                port,
                result.getMetadata().get("port")
        );
    }

    // -------------------------------------------------------------------------
    // Configuration validation
    // -------------------------------------------------------------------------

    @Test
    void rejectsMissingType() {

        PluginContext context = context(
                Map.of(
                        "url",
                        "http://localhost"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsUnsupportedType() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "PING"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsMissingHttpUrl() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "HTTP"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsInvalidHttpScheme() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "HTTP",
                        "url",
                        "ftp://example.com"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsHttpUrlWithoutHost() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "HTTP",
                        "url",
                        "http:///health"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsCredentialsInHttpUrl() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "HTTP",
                        "url",
                        "https://user:password@example.com/health"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsInvalidExpectedStatus() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "HTTP",
                        "url",
                        "http://localhost",
                        "expectedStatus",
                        700
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsInvalidTimeout() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "HTTP",
                        "url",
                        "http://localhost",
                        "timeoutSeconds",
                        0
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsExcessiveTimeout() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "HTTP",
                        "url",
                        "http://localhost",
                        "timeoutSeconds",
                        301
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsMissingTcpHost() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "TCP",
                        "port",
                        5432
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsInvalidTcpPort() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "TCP",
                        "host",
                        "localhost",
                        "port",
                        0
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsTcpPortAboveMaximum() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "TCP",
                        "host",
                        "localhost",
                        "port",
                        65536
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsInvalidTcpHost() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "TCP",
                        "host",
                        "http://localhost",
                        "port",
                        5432
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsNonIntegerTimeout() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "HTTP",
                        "url",
                        "http://localhost",
                        "timeoutSeconds",
                        "five"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsNonIntegerPort() {

        PluginContext context = context(
                Map.of(
                        "type",
                        "TCP",
                        "host",
                        "localhost",
                        "port",
                        "postgres"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void startHttpServer(
            int statusCode,
            String responseBody)
            throws IOException {

        httpServer = HttpServer.create(
                new InetSocketAddress(0),
                0
        );

        httpServer.createContext(
                "/health",
                exchange -> sendResponse(
                        exchange,
                        statusCode,
                        responseBody
                )
        );

        httpServer.setExecutor(
                Executors.newCachedThreadPool()
        );

        httpServer.start();
    }

    private void startRedirectServer()
            throws IOException {

        httpServer = HttpServer.create(
                new InetSocketAddress(0),
                0
        );

        httpServer.createContext(
                "/redirect",
                exchange -> {

                    exchange.getResponseHeaders()
                            .add(
                                    "Location",
                                    "/health"
                            );

                    exchange.sendResponseHeaders(
                            302,
                            -1
                    );

                    exchange.close();
                }
        );

        httpServer.createContext(
                "/health",
                exchange -> sendResponse(
                        exchange,
                        200,
                        "redirected"
                )
        );

        httpServer.setExecutor(
                Executors.newCachedThreadPool()
        );

        httpServer.start();
    }

    private void sendResponse(
            HttpExchange exchange,
            int statusCode,
            String body)
            throws IOException {

        byte[] bytes = body.getBytes(
                StandardCharsets.UTF_8
        );

        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        "text/plain"
                );

        exchange.sendResponseHeaders(
                statusCode,
                bytes.length
        );

        try (OutputStream outputStream =
                     exchange.getResponseBody()) {

            outputStream.write(bytes);
        }
    }

    private String serverUrl() {

        return "http://127.0.0.1:"
                + httpServer.getAddress().getPort()
                + "/health";
    }

    private PluginContext httpContext(
            String url,
            Map<String, Object> extraConfiguration) {

        var configuration =
                new java.util.LinkedHashMap<String, Object>();

        configuration.put(
                "type",
                "HTTP"
        );

        configuration.put(
                "url",
                url
        );

        configuration.putAll(
                extraConfiguration
        );

        return context(configuration);
    }

    private PluginContext tcpContext(
            String host,
            int port,
            Map<String, Object> extraConfiguration) {

        var configuration =
                new java.util.LinkedHashMap<String, Object>();

        configuration.put(
                "type",
                "TCP"
        );

        configuration.put(
                "host",
                host
        );

        configuration.put(
                "port",
                port
        );

        configuration.putAll(
                extraConfiguration
        );

        return context(configuration);
    }

    private PluginContext context(
            Map<String, Object> configuration) {

        return new PluginContext(
                "health-check-test",
                30,
                configuration,
                Map.of()
        );
    }
}