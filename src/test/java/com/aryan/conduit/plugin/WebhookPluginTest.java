package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

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

        when(restTemplate.exchange(
                eq("https://example.com/webhook"),
                eq(HttpMethod.POST),
                any(),
                eq(String.class)
        )).thenReturn(
                ResponseEntity.ok("{\"ok\":true}")
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

        verify(restTemplate).exchange(
                eq("https://example.com/webhook"),
                eq(HttpMethod.POST),
                any(),
                eq(String.class)
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