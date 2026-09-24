package com.aryan.conduit.execution.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                        "userId", 42,
                        "token", "abc123"
                )
        );

        String config =
                """
                {
                    "url": "https://example.com/users/${userId}",
                    "token": "${token}"
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
                "abc123",
                objectMapper.readTree(resolved)
                        .get("token")
                        .asText()
        );
    }
    @Test
    void shouldResolveNestedTaskOutput() throws JsonProcessingException {

        when(
                expressionContextService.buildContext(
                        workflowExecutionId
                )
        ).thenReturn(
                Map.of(
                        "fetchUser",
                        Map.of(
                                "success", true,
                                "output",
                                Map.of(
                                        "userId", 42,
                                        "email",
                                        "user@example.com"
                                )
                        )
                )
        );

        String config =
                """
                {
                    "url": "https://example.com/users/${fetchUser.output.userId}",
                    "email": "${fetchUser.output.email}"
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
}