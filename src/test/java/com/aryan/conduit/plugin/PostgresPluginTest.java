package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresqlPluginTest {

    private final PostgresqlPlugin plugin =
            new PostgresqlPlugin(new ObjectMapper());

    @Test
    void exposesMetadata() {

        assertEquals(
                "POSTGRESQL",
                plugin.metadata().type()
        );

        assertEquals(
                "PostgreSQL",
                plugin.metadata().displayName()
        );

        assertTrue(
                plugin.metadata()
                        .configurationSchema()
                        .containsKey("properties")
        );
    }

    @Test
    void rejectsMissingConfiguration() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(null)
        );
    }

    @Test
    void rejectsMissingUrl() {

        Map<String, Object> configuration =
                Map.of(
                        "username", "postgres",
                        "password", "postgres",
                        "query", "SELECT 1"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(configuration)
        );
    }

    @Test
    void rejectsMissingQuery() {

        Map<String, Object> configuration =
                Map.of(
                        "url",
                        "jdbc:postgresql://localhost:5432/test",
                        "username",
                        "postgres",
                        "password",
                        "postgres"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(configuration)
        );
    }

    @Test
    void rejectsInvalidParameters() {

        Map<String, Object> configuration =
                Map.of(
                        "url",
                        "jdbc:postgresql://localhost:5432/test",
                        "username",
                        "postgres",
                        "password",
                        "postgres",
                        "query",
                        "SELECT 1",
                        "parameters",
                        "not-an-array"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(configuration)
        );
    }

    @Test
    void rejectsInvalidTimeout() {

        Map<String, Object> configuration =
                Map.of(
                        "url",
                        "jdbc:postgresql://localhost:5432/test",
                        "username",
                        "postgres",
                        "password",
                        "postgres",
                        "query",
                        "SELECT 1",
                        "timeoutSeconds",
                        0
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(configuration)
        );
    }

    @Test
    void returnsFailureWhenDatabaseIsUnavailable() {

        PluginContext context =
                new PluginContext(
                        "postgres-test",
                        30,
                        Map.of(
                                "url",
                                "jdbc:postgresql://localhost:59999/test",
                                "username",
                                "postgres",
                                "password",
                                "postgres",
                                "query",
                                "SELECT 1"
                        ),
                        Map.of()
                );

        var result =
                plugin.execute(context);

        assertFalse(result.isSuccess());
        assertEquals(
                "POSTGRESQL",
                result.metadata().get("operation")
        );
    }
}