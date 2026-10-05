package com.aryan.conduit.execution.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

public class ConfigurationResolverTest {

    private ConfigurationResolver configurationResolver;
    private ExpressionContextService expressionContextService;
    private ObjectMapper objectMapper;

    private final Long workflowExecutionId = 1L;

    @BeforeEach
    void setUp() {
        expressionContextService =
                Mockito.mock(ExpressionContextService.class);

        configurationResolver =
                new ConfigurationResolver(
                        expressionContextService
                );

        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldResolveVariables() throws Exception {

        when(
                expressionContextService.buildContext(
                        workflowExecutionId
                )
        ).thenReturn(
                Map.of(
                        "variables", Map.of("userId", 42, "requestId", "a/b?x=1&y=2"),
                        "tasks", Map.of("fetchUser", Map.of("output", Map.of("userId", 42)))
                )
        );

        String config =
                """
                {
                    "url": "https://example.com/users/${variables.userId}",
                    "timeout": 30,
                    "enabled": true,
                    "headers": {"X-Request": "${variables.requestId}"},
                    "resultId": "${tasks.fetchUser.output.userId}"
                }
                """;

        String resolved =
                configurationResolver.resolve(
                        workflowExecutionId,
                        config
                );

        assertEquals(
                "https://example.com/users/42",
                objectMapper.readTree(resolved)
                        .get("url")
                        .asText()
        );

        var json = objectMapper.readTree(resolved);
        assertEquals(30, json.get("timeout").asInt());
        assertEquals(true, json.get("enabled").asBoolean());
        assertEquals("a/b?x=1&y=2", json.path("headers").path("X-Request").asText());
        assertEquals(42, json.get("resultId").asInt());
    }
    @Test
    void shouldResolveNestedTaskOutput() throws JsonProcessingException {

        when(
                expressionContextService.buildContext(
                        workflowExecutionId
                )
        ).thenReturn(
                Map.of(
                        "tasks", Map.of("fetchUser", Map.of("output", Map.of(
                                "userId", 42, "email", "user@example.com")))
                )
        );

        String config =
                """
                {
                    "url": "https://example.com/users/${tasks.fetchUser.output.userId}",
                    "email": "${tasks.fetchUser.output.email}"
                }
                """;

        String resolved =
                configurationResolver.resolve(
                        workflowExecutionId,
                        config
                );

        assertEquals(
                "https://example.com/users/42",
                objectMapper.readTree(resolved)
                        .get("url")
                        .asText()
        );

        assertEquals(
                "user@example.com",
                objectMapper.readTree(resolved)
                        .get("email")
                        .asText()
        );
    }

    @Test
    void rejectsMissingNullSecretAndExecutablePaths() {
        when(expressionContextService.buildContext(workflowExecutionId)).thenReturn(Map.of(
                "variables", java.util.Collections.singletonMap("nullable", null)));
        assertThrows(IllegalArgumentException.class, () -> configurationResolver.resolve(workflowExecutionId, "{\"x\":\"${variables.missing}\"}"));
        assertThrows(IllegalArgumentException.class, () -> configurationResolver.resolve(workflowExecutionId, "{\"x\":\"${variables.nullable}\"}"));
        assertThrows(IllegalArgumentException.class, () -> configurationResolver.resolve(workflowExecutionId, "{\"x\":\"${variables.password}\"}"));
        assertThrows(IllegalArgumentException.class, () -> configurationResolver.resolve(workflowExecutionId, "{\"x\":\"${T(java.lang.Runtime).getRuntime()}\"}"));
        assertThrows(IllegalArgumentException.class, () -> configurationResolver.resolve(workflowExecutionId, "{\"x\":\"${variables.missing\"}"));
    }
}
