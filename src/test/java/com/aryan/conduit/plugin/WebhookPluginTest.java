package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.net.URI;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhookPluginTest {

    @Test
    void webhookShouldSendRequest() {

        RestTemplate restTemplate =
                mock(RestTemplate.class);

        ObjectMapper objectMapper =
                new ObjectMapper();

        when(restTemplate.execute(
                eq(URI.create("https://example.com/webhook")),
                eq(HttpMethod.POST),
                any(RequestCallback.class),
                any(ResponseExtractor.class)
        )).thenReturn(
                new BoundedHttpResponse(200, "{\"ok\":true}", false)
        );

        WebhookPlugin plugin =
                new WebhookPlugin(
                        restTemplate,
                        objectMapper
                );

        PluginContext context =
                new PluginContext(
                        "webhook-task",
                        30,
                        Map.of(
                                "url",
                                "https://example.com/webhook",
                                "method",
                                "POST",
                                "allowPrivateNetwork", true,
                                "body",
                                Map.of(
                                        "message",
                                        "hello"
                                )
                        ),
                        Map.of()
                );

        PluginResult result =
                plugin.execute(context);

        assertTrue(result.success());

        assertEquals(
                "{\"ok\":true}",
                result.output()
        );

        verify(restTemplate).execute(
                eq(URI.create("https://example.com/webhook")),
                eq(HttpMethod.POST),
                any(RequestCallback.class),
                any(ResponseExtractor.class)
        );
    }

    @Test
    void webhookShouldRejectInvalidUrl() {

        WebhookPlugin plugin =
                new WebhookPlugin(
                        mock(RestTemplate.class),
                        new ObjectMapper()
                );

        assertThrows(
                RuntimeException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "url",
                                "ftp://example.com"
                        )
                )
        );
    }
}
