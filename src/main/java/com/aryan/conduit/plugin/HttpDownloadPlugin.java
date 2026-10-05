package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import com.aryan.conduit.plugin.security.SsrfAddressPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Downloads a bounded public HTTP response directly into the managed execution workspace. */
@Component
@RequiredArgsConstructor
public class HttpDownloadPlugin implements WorkflowPlugin {
    private static final long DEFAULT_LIMIT = 50L * 1024 * 1024;
    private static final long HARD_LIMIT = 100L * 1024 * 1024;
    private final RestTemplate restTemplate;

    @Override public PluginMetadata metadata() {
        return new PluginMetadata("HTTP_DOWNLOAD", "HTTP File Download",
                "Download a bounded HTTP response into the managed execution workspace.", "1.0.0",
                Map.of("type", "object", "required", List.of("url", "path"), "properties", Map.of(
                        "url", Map.of("type", "string", "format", "uri"),
                        "path", Map.of("type", "string", "description", "Workspace-relative destination path"),
                        "maxBytes", Map.of("type", "integer", "default", DEFAULT_LIMIT, "maximum", HARD_LIMIT),
                        "overwrite", Map.of("type", "boolean", "default", false),
                        "createDirectories", Map.of("type", "boolean", "default", true),
                        "headers", Map.of("type", "object", "description", "Optional request headers; use credential references for authorization values"),
                        "allowPrivateNetwork", Map.of("type", "boolean", "default", false))));
    }

    @Override public void validateConfiguration(Map<String, Object> c) {
        if (!(c.get("url") instanceof String u) || u.isBlank()) throw new PluginConfigurationException("HTTP_DOWNLOAD requires url");
        if (!(c.get("path") instanceof String p) || p.isBlank() || p.startsWith("/") || p.contains("\\") || p.contains(".."))
            throw new PluginConfigurationException("HTTP_DOWNLOAD path must be a safe workspace-relative path");
        Object limit = c.get("maxBytes");
        if (limit != null && (!(limit instanceof Number n) || n.longValue() < 1 || n.longValue() > HARD_LIMIT))
            throw new PluginConfigurationException("HTTP_DOWNLOAD maxBytes must be between 1 and 104857600");
        if (c.get("allowPrivateNetwork") != null && !(c.get("allowPrivateNetwork") instanceof Boolean))
            throw new PluginConfigurationException("HTTP_DOWNLOAD allowPrivateNetwork must be boolean");
        if (c.get("headers") != null && (!(c.get("headers") instanceof Map<?, ?> headers) || headers.size() > 50))
            throw new PluginConfigurationException("HTTP_DOWNLOAD headers must be an object with at most 50 entries");
        SsrfAddressPolicy.validate(URI.create(u), Boolean.TRUE.equals(c.get("allowPrivateNetwork")));
    }

    @Override public PluginResult execute(PluginContext context) {
        validateConfiguration(context.configuration());
        if (context.workspace() == null) throw new PluginConfigurationException("HTTP_DOWNLOAD requires the managed execution workspace");
        Map<String, Object> c = context.configuration();
        URI uri = URI.create(String.valueOf(c.get("url")));
        long limit = c.get("maxBytes") instanceof Number n ? n.longValue() : DEFAULT_LIMIT;
        try {
            BoundedHttpResponseInfo result = restTemplate.execute(uri, HttpMethod.GET, request -> {
                if (c.get("headers") instanceof Map<?, ?> headers) headers.forEach((name, value) -> {
                    if (name != null && value != null) request.getHeaders().set(String.valueOf(name), String.valueOf(value));
                });
            }, response -> {
                int status = response.getStatusCode().value();
                if (status < 200 || status >= 300) throw new PluginHttpFailureException(status, status == 408 || status == 429 || status >= 500, retryAfter(response.getHeaders().getFirst("Retry-After")));
                long length = response.getHeaders().getContentLength();
                if (length > limit) throw new PluginConfigurationException("HTTP_DOWNLOAD response exceeds configured maxBytes");
                long written = context.workspace().writeAtomic(String.valueOf(c.get("path")),
                        new LimitedInputStream(response.getBody(), limit), Boolean.TRUE.equals(c.get("overwrite")),
                        !Boolean.FALSE.equals(c.get("createDirectories")));
                return new BoundedHttpResponseInfo(status, written);
            });
            if (result == null) throw new IllegalStateException("HTTP_DOWNLOAD returned no response");
            return PluginResult.builder().success(true).output("Downloaded " + result.bytes + " bytes")
                    .metadata(Map.of("operation", "DOWNLOAD", "path", c.get("path"), "sizeBytes", result.bytes,
                            "statusCode", result.status, "host", uri.getHost())).build();
        } catch (PluginHttpFailureException e) { throw e; }
        catch (PluginConfigurationException e) { throw e; }
        catch (Exception e) { return PluginResult.builder().success(false).output("HTTP download failed: " + e.getClass().getSimpleName()).build(); }
    }

    private static long retryAfter(String value) {
        if (value == null) return 0;
        try { return Math.max(0, Long.parseLong(value.trim()) * 1000); }
        catch (NumberFormatException ignored) { try { return Math.max(0, java.time.Duration.between(java.time.Instant.now(), java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); } catch (Exception ignoredAgain) { return 0; } }
    }
    private record BoundedHttpResponseInfo(int status, long bytes) { }
    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private final long max; private long count;
        LimitedInputStream(java.io.InputStream in, long max) { super(in); this.max = max; }
        @Override public int read() throws java.io.IOException { int b = super.read(); if (b >= 0 && ++count > max) throw new java.io.IOException("HTTP_DOWNLOAD exceeded maxBytes"); return b; }
        @Override public int read(byte[] b, int off, int len) throws java.io.IOException { int n = super.read(b, off, (int)Math.min(len, max - count + 1)); if (n > 0 && (count += n) > max) throw new java.io.IOException("HTTP_DOWNLOAD exceeded maxBytes"); return n; }
    }
}
