package com.aryan.conduit.plugin;

import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

record BoundedHttpResponse(int statusCode, String body, boolean truncated, String retryAfter) {
    static final int MAX_BYTES = 1024 * 1024;
    static BoundedHttpResponse read(ClientHttpResponse response) throws IOException {
        byte[] bytes = response.getBody().readNBytes(MAX_BYTES + 1);
        boolean truncated = bytes.length > MAX_BYTES;
        int length = Math.min(bytes.length, MAX_BYTES);
        String body = new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8);
        if (truncated) body += "\n[response truncated]";
        return new BoundedHttpResponse(response.getStatusCode().value(), body, truncated,
                response.getHeaders().getFirst("Retry-After"));
    }
}
