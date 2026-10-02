package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RedisPlugin implements WorkflowPlugin {

    private static final int DEFAULT_PORT = 6379;

    private static final String OPERATION_GET = "GET";
    private static final String OPERATION_SET = "SET";
    private static final String OPERATION_DELETE = "DELETE";
    private static final String OPERATION_EXISTS = "EXISTS";

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata(
                "REDIS",
                "Redis",
                "Read and write values in a Redis database.",
                "1.0.0",
                Map.of(
                        "type", "object",
                        "required", List.of(
                                "host",
                                "operation",
                                "key"
                        ),
                        "properties", Map.of(
                                "host", Map.of(
                                        "type", "string",
                                        "description",
                                        "Redis server hostname or IP address"
                                ),
                                "port", Map.of(
                                        "type", "integer",
                                        "minimum", 1,
                                        "maximum", 65535,
                                        "default", DEFAULT_PORT,
                                        "description",
                                        "Redis server port"
                                ),
                                "password", Map.of(
                                        "type", "string",
                                        "description",
                                        "Optional Redis password"
                                ),
                                "database", Map.of(
                                        "type", "integer",
                                        "minimum", 0,
                                        "default", 0,
                                        "description",
                                        "Redis logical database index"
                                ),
                                "operation", Map.of(
                                        "type", "string",
                                        "enum", List.of(
                                                OPERATION_GET,
                                                OPERATION_SET,
                                                OPERATION_DELETE,
                                                OPERATION_EXISTS
                                        )
                                ),
                                "key", Map.of(
                                        "type", "string",
                                        "description",
                                        "Redis key"
                                ),
                                "value", Map.of(
                                        "type", "string",
                                        "description",
                                        "Value used by SET"
                                ),
                                "ttlSeconds", Map.of(
                                        "type", "integer",
                                        "minimum", 1,
                                        "description",
                                        "Optional expiration time for SET"
                                )
                        )
                )
        );
    }

    @Override
    public void validateConfiguration(
            Map<String, Object> configuration
    ) throws PluginConfigurationException {

        if (configuration == null) {
            throw new PluginConfigurationException(
                    "REDIS configuration is required"
            );
        }

        requireNonBlank(
                configuration,
                "host",
                "REDIS requires a non-empty 'host'"
        );

        requireNonBlank(
                configuration,
                "operation",
                "REDIS requires a non-empty 'operation'"
        );

        requireNonBlank(
                configuration,
                "key",
                "REDIS requires a non-empty 'key'"
        );

        String operation =
                String.valueOf(
                        configuration.get("operation")
                ).toUpperCase();

        if (!List.of(
                OPERATION_GET,
                OPERATION_SET,
                OPERATION_DELETE,
                OPERATION_EXISTS
        ).contains(operation)) {

            throw new PluginConfigurationException(
                    "Unsupported REDIS operation: " + operation
            );
        }

        if (OPERATION_SET.equals(operation)
                && !configuration.containsKey("value")) {

            throw new PluginConfigurationException(
                    "REDIS SET requires 'value'"
            );
        }

        validatePositiveInteger(
                configuration,
                "port",
                "REDIS 'port' must be a positive integer"
        );

        validateNonNegativeInteger(
                configuration,
                "database",
                "REDIS 'database' must be a non-negative integer"
        );

        validatePositiveInteger(
                configuration,
                "ttlSeconds",
                "REDIS 'ttlSeconds' must be a positive integer"
        );
    }

    @Override
    public PluginResult execute(
            PluginContext context
    ) {

        validateConfiguration(
                context.configuration()
        );

        Map<String, Object> configuration =
                context.configuration();

        String host =
                String.valueOf(
                        configuration.get("host")
                );

        int port =
                configuration.containsKey("port")
                        ? ((Number) configuration.get("port")).intValue()
                        : DEFAULT_PORT;

        String password =
                configuration.get("password") == null
                        ? null
                        : String.valueOf(
                        configuration.get("password")
                );

        int database =
                configuration.containsKey("database")
                        ? ((Number) configuration.get("database")).intValue()
                        : 0;

        String operation =
                String.valueOf(
                        configuration.get("operation")
                ).toUpperCase();

        String key =
                String.valueOf(
                        configuration.get("key")
                );

        try {
            RedisStandaloneConfiguration redisConfiguration =
                    new RedisStandaloneConfiguration(
                            host,
                            port
                    );

            redisConfiguration.setDatabase(database);

            if (password != null && !password.isBlank()) {
                redisConfiguration.setPassword(password);
            }

            LettuceConnectionFactory connectionFactory =
                    new LettuceConnectionFactory(
                            redisConfiguration
                    );

            connectionFactory.afterPropertiesSet();

            try {
                return executeOperation(
                        connectionFactory,
                        configuration,
                        operation,
                        key
                );
            } finally {
                connectionFactory.destroy();
            }

        } catch (Exception ex) {

            return PluginResult.builder()
                    .success(false)
                    .output(
                            ex.getMessage() == null
                                    ? ex.getClass().getSimpleName()
                                    : ex.getMessage()
                    )
                    .metadata(
                            Map.of(
                                    "operation",
                                    operation,
                                    "key",
                                    key
                            )
                    )
                    .build();
        }
    }

    private PluginResult executeOperation(
            LettuceConnectionFactory connectionFactory,
            Map<String, Object> configuration,
            String operation,
            String key
    ) {

        try (RedisConnection connection =
                     connectionFactory.getConnection()) {

            byte[] keyBytes =
                    key.getBytes(StandardCharsets.UTF_8);

            return switch (operation) {

                case OPERATION_GET ->
                        executeGet(
                                connection,
                                key,
                                keyBytes
                        );

                case OPERATION_SET ->
                        executeSet(
                                connection,
                                configuration,
                                key,
                                keyBytes
                        );

                case OPERATION_DELETE ->
                        executeDelete(
                                connection,
                                key,
                                keyBytes
                        );

                case OPERATION_EXISTS ->
                        executeExists(
                                connection,
                                key,
                                keyBytes
                        );

                default ->
                        throw new PluginConfigurationException(
                                "Unsupported REDIS operation: "
                                        + operation
                        );
            };
        }
    }

    private PluginResult executeGet(
            RedisConnection connection,
            String key,
            byte[] keyBytes
    ) {

        byte[] value =
                connection.stringCommands()
                        .get(keyBytes);

        String output =
                value == null
                        ? null
                        : new String(
                        value,
                        StandardCharsets.UTF_8
                );

        return PluginResult.builder()
                .success(true)
                .output(output)
                .metadata(
                        Map.of(
                                "operation",
                                OPERATION_GET,
                                "key",
                                key,
                                "found",
                                value != null
                        )
                )
                .build();
    }

    private PluginResult executeSet(
            RedisConnection connection,
            Map<String, Object> configuration,
            String key,
            byte[] keyBytes
    ) {

        String value =
                String.valueOf(
                        configuration.get("value")
                );

        byte[] valueBytes =
                value.getBytes(
                        StandardCharsets.UTF_8
                );

        connection.stringCommands()
                .set(
                        keyBytes,
                        valueBytes
                );

        Object ttl =
                configuration.get("ttlSeconds");

        if (ttl != null) {
            connection.keyCommands()
                    .expire(
                            keyBytes,
                            ((Number) ttl).longValue()
                    );
        }

        return PluginResult.builder()
                .success(true)
                .output("Value stored successfully")
                .metadata(
                        Map.of(
                                "operation",
                                OPERATION_SET,
                                "key",
                                key,
                                "ttlSeconds",
                                ttl == null
                                        ? 0
                                        : ((Number) ttl).longValue()
                        )
                )
                .build();
    }

    private PluginResult executeDelete(
            RedisConnection connection,
            String key,
            byte[] keyBytes
    ) {

        Long deleted =
                connection.keyCommands()
                        .del(keyBytes);

        boolean existed =
                deleted != null && deleted > 0;

        return PluginResult.builder()
                .success(true)
                .output(
                        existed
                                ? "Key deleted successfully"
                                : "Key did not exist"
                )
                .metadata(
                        Map.of(
                                "operation",
                                OPERATION_DELETE,
                                "key",
                                key,
                                "deleted",
                                existed
                        )
                )
                .build();
    }

    private PluginResult executeExists(
            RedisConnection connection,
            String key,
            byte[] keyBytes
    ) {

        Boolean exists =
                connection.keyCommands()
                        .exists(keyBytes);

        boolean found =
                Boolean.TRUE.equals(exists);

        return PluginResult.builder()
                .success(true)
                .output(
                        String.valueOf(found)
                )
                .metadata(
                        Map.of(
                                "operation",
                                OPERATION_EXISTS,
                                "key",
                                key,
                                "exists",
                                found
                        )
                )
                .build();
    }

    private void requireNonBlank(
            Map<String, Object> configuration,
            String key,
            String message
    ) {

        Object value =
                configuration.get(key);

        if (!(value instanceof String string)
                || string.isBlank()) {

            throw new PluginConfigurationException(
                    message
            );
        }
    }

    private void validatePositiveInteger(
            Map<String, Object> configuration,
            String key,
            String message
    ) {

        Object value =
                configuration.get(key);

        if (value != null
                && (!(value instanceof Number number)
                || number.intValue() <= 0)) {

            throw new PluginConfigurationException(
                    message
            );
        }
    }

    private void validateNonNegativeInteger(
            Map<String, Object> configuration,
            String key,
            String message
    ) {

        Object value =
                configuration.get(key);

        if (value != null
                && (!(value instanceof Number number)
                || number.intValue() < 0)) {

            throw new PluginConfigurationException(
                    message
            );
        }
    }
}