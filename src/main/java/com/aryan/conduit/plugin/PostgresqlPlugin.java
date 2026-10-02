package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PostgresqlPlugin implements WorkflowPlugin {

    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    private final ObjectMapper objectMapper;

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata(
                "POSTGRESQL",
                "PostgreSQL",
                "Execute parameterized SQL queries against a PostgreSQL database.",
                "1.0.0",
                Map.of(
                        "type", "object",
                        "required", List.of(
                                "url",
                                "username",
                                "password",
                                "query"
                        ),
                        "properties", Map.of(
                                "url", Map.of(
                                        "type", "string",
                                        "description",
                                        "JDBC PostgreSQL connection URL"
                                ),
                                "username", Map.of(
                                        "type", "string",
                                        "description",
                                        "Database username"
                                ),
                                "password", Map.of(
                                        "type", "string",
                                        "description",
                                        "Database password"
                                ),
                                "query", Map.of(
                                        "type", "string",
                                        "description",
                                        "Parameterized SQL query using ? placeholders"
                                ),
                                "parameters", Map.of(
                                        "type", "array",
                                        "description",
                                        "Values for ? placeholders in the SQL query"
                                ),
                                "timeoutSeconds", Map.of(
                                        "type", "integer",
                                        "minimum", 1,
                                        "default",
                                        DEFAULT_QUERY_TIMEOUT_SECONDS
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
                    "POSTGRESQL configuration is required"
            );
        }

        requireNonBlank(
                configuration,
                "url",
                "POSTGRESQL requires a non-empty 'url'"
        );

        requireNonBlank(
                configuration,
                "username",
                "POSTGRESQL requires a non-empty 'username'"
        );

        if (!configuration.containsKey("password")) {
            throw new PluginConfigurationException(
                    "POSTGRESQL requires 'password'"
            );
        }

        requireNonBlank(
                configuration,
                "query",
                "POSTGRESQL requires a non-empty 'query'"
        );

        Object parameters =
                configuration.get("parameters");

        if (parameters != null
                && !(parameters instanceof List<?>)) {

            throw new PluginConfigurationException(
                    "POSTGRESQL 'parameters' must be an array"
            );
        }

        Object timeout =
                configuration.get("timeoutSeconds");

        if (timeout != null) {
            if (!(timeout instanceof Number number)
                    || number.intValue() <= 0) {

                throw new PluginConfigurationException(
                        "POSTGRESQL 'timeoutSeconds' must be a positive integer"
                );
            }
        }
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

        String url =
                String.valueOf(
                        configuration.get("url")
                );

        String username =
                String.valueOf(
                        configuration.get("username")
                );

        String password =
                String.valueOf(
                        configuration.get("password")
                );

        String query =
                String.valueOf(
                        configuration.get("query")
                );

        int timeoutSeconds =
                configuration.containsKey("timeoutSeconds")
                        ? ((Number) configuration.get(
                        "timeoutSeconds"
                )).intValue()
                        : DEFAULT_QUERY_TIMEOUT_SECONDS;

        List<?> parameters =
                configuration.get("parameters")
                        instanceof List<?> list
                        ? list
                        : List.of();

        try {
            DataSource dataSource =
                    createDataSource(
                            url,
                            username,
                            password
                    );

            JdbcTemplate jdbcTemplate =
                    new JdbcTemplate(dataSource);

            jdbcTemplate.setQueryTimeout(
                    timeoutSeconds
            );

            String normalized =
                    query.trim().toLowerCase();

            if (normalized.startsWith("select")
                    || normalized.startsWith("with")
                    || normalized.startsWith("show")
                    || normalized.startsWith("explain")) {

                List<Map<String, Object>> rows =
                        jdbcTemplate.queryForList(
                                query,
                                parameters.toArray()
                        );

                return PluginResult.builder()
                        .success(true)
                        .output(
                                objectMapper.writeValueAsString(rows)
                        )
                        .metadata(
                                Map.of(
                                        "operation",
                                        "QUERY",
                                        "rowCount",
                                        rows.size()
                                )
                        )
                        .build();
            }

            int affectedRows =
                    jdbcTemplate.update(
                            query,
                            parameters.toArray()
                    );

            return PluginResult.builder()
                    .success(true)
                    .output(
                            "Query executed successfully"
                    )
                    .metadata(
                            Map.of(
                                    "operation",
                                    "UPDATE",
                                    "affectedRows",
                                    affectedRows
                            )
                    )
                    .build();

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
                                    "POSTGRESQL"
                            )
                    )
                    .build();
        }
    }

    private DataSource createDataSource(
            String url,
            String username,
            String password
    ) {

        DriverManagerDataSource dataSource =
                new DriverManagerDataSource();

        dataSource.setDriverClassName(
                "org.postgresql.Driver"
        );

        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);

        return dataSource;
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
}