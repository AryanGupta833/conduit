package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisPluginTest {

    private final RedisPlugin plugin =
            new RedisPlugin();

    @Test
    void exposesMetadata() {

        assertEquals(
                "REDIS",
                plugin.metadata().type()
        );

        assertEquals(
                "Redis",
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
    void rejectsMissingHost() {

        Map<String, Object> configuration =
                Map.of(
                        "operation",
                        "GET",
                        "key",
                        "test"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(configuration)
        );
    }

    @Test
    void rejectsUnsupportedOperation() {

        Map<String, Object> configuration =
                Map.of(
                        "host",
                        "localhost",
                        "operation",
                        "INVALID",
                        "key",
                        "test"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(configuration)
        );
    }

    @Test
    void rejectsSetWithoutValue() {

        Map<String, Object> configuration =
                Map.of(
                        "host",
                        "localhost",
                        "operation",
                        "SET",
                        "key",
                        "test"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(configuration)
        );
    }

    @Test
    void rejectsInvalidPort() {

        Map<String, Object> configuration =
                Map.of(
                        "host",
                        "localhost",
                        "port",
                        0,
                        "operation",
                        "GET",
                        "key",
                        "test"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(configuration)
        );
    }

    @Test
    void rejectsNegativeDatabase() {

        Map<String, Object> configuration =
                Map.of(
                        "host",
                        "localhost",
                        "database",
                        -1,
                        "operation",
                        "GET",
                        "key",
                        "test"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(configuration)
        );
    }

    @Test
    void rejectsInvalidTtl() {

        Map<String, Object> configuration =
                Map.of(
                        "host",
                        "localhost",
                        "operation",
                        "SET",
                        "key",
                        "test",
                        "value",
                        "value",
                        "ttlSeconds",
                        0
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(configuration)
        );
    }

    @Test
    void returnsFailureWhenRedisIsUnavailable() {

        PluginContext context =
                new PluginContext(
                        "redis-test",
                        30,
                        Map.of(
                                "host",
                                "localhost",
                                "port",
                                59999,
                                "operation",
                                "GET",
                                "key",
                                "test"
                        ),
                        Map.of()
                );

        var result =
                plugin.execute(context);

        assertFalse(result.isSuccess());

        assertEquals(
                "GET",
                result.metadata().get("operation")
        );

        assertEquals(
                "test",
                result.metadata().get("key")
        );
    }
}