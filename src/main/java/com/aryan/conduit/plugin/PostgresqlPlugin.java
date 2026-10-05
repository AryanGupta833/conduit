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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PostgresqlPlugin implements WorkflowPlugin {

    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    private final ObjectMapper objectMapper;
    private PostgresDataSourceRegistry dataSourceRegistry;

    @Autowired(required = false)
    void setDataSourceRegistry(PostgresDataSourceRegistry dataSourceRegistry) { this.dataSourceRegistry = dataSourceRegistry; }

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
                                ),
                                "namedParameters", Map.of("type", "object", "description", "Named SQL parameters, e.g. :customerId"),
                                "transaction", Map.of("type", "boolean", "default", false, "description", "Execute this single task query in an explicit transaction"),
                                "maxRows", Map.of(
                                        "type", "integer", "minimum", 1, "maximum", 10000, "default", 1000
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
        if (configuration.get("namedParameters") != null && !(configuration.get("namedParameters") instanceof Map<?, ?>))
            throw new PluginConfigurationException("POSTGRESQL 'namedParameters' must be an object");
        if (configuration.get("namedParameters") instanceof Map<?, ?> && configuration.get("parameters") instanceof List<?> list && !list.isEmpty())
            throw new PluginConfigurationException("Use either namedParameters or positional parameters, not both");
        if (configuration.get("transaction") != null && !(configuration.get("transaction") instanceof Boolean))
            throw new PluginConfigurationException("POSTGRESQL transaction must be a boolean");
        Object maxRows = configuration.get("maxRows");
        if (maxRows != null && (!(maxRows instanceof Number number) || number.intValue() < 1 || number.intValue() > 10000))
            throw new PluginConfigurationException("POSTGRESQL 'maxRows' must be between 1 and 10000");
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

        java.sql.Connection transactionConnection = null;
        try {
            DataSource baseDataSource = createDataSource(url, username, password);
            DataSource dataSource = baseDataSource;
            if (Boolean.TRUE.equals(configuration.get("transaction"))) {
                transactionConnection = baseDataSource.getConnection();
                transactionConnection.setAutoCommit(false);
                dataSource = new SingleConnectionDataSource(transactionConnection, true);
            }

        JdbcTemplate jdbcTemplate =
                new JdbcTemplate(dataSource);

        jdbcTemplate.setQueryTimeout(
                timeoutSeconds
        );
        jdbcTemplate.setMaxRows(configuration.get("maxRows") instanceof Number n ? n.intValue() : 1000);
        jdbcTemplate.setFetchSize(100);

            String normalized =
                    query.trim().toLowerCase();
            NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
            Map<String, Object> namedParameters = new java.util.LinkedHashMap<>();
            if (configuration.get("namedParameters") instanceof Map<?, ?> configured) {
                for (var entry : configured.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || !key.matches("[A-Za-z_][A-Za-z0-9_]*"))
                        throw new PluginConfigurationException("POSTGRESQL named parameter names must be SQL identifiers");
                    namedParameters.put(key, entry.getValue());
                }
            }
            boolean hasNamed = !namedParameters.isEmpty();

            if (normalized.startsWith("select")
                    || normalized.startsWith("with")
                    || normalized.startsWith("show")
                    || normalized.startsWith("explain")) {

                List<Map<String, Object>> rows = hasNamed
                        ? named.queryForList(query, namedParameters)
                        : jdbcTemplate.queryForList(query, parameters.toArray());

                if (transactionConnection != null) transactionConnection.commit();
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

            int affectedRows = hasNamed ? named.update(query, namedParameters) : jdbcTemplate.update(query, parameters.toArray());
            if (transactionConnection != null) transactionConnection.commit();

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
            if (transactionConnection != null) try { transactionConnection.rollback(); } catch (java.sql.SQLException ignored) { }

            return PluginResult.builder()
                    .success(false)
                    .output("PostgreSQL operation failed")
                    .metadata(
                            Map.of(
                                    "operation",
                                    "POSTGRESQL"
                            )
                    )
                    .build();
        } finally {
            if (transactionConnection != null) try { transactionConnection.close(); } catch (java.sql.SQLException ignored) { }
        }
    }

    private DataSource createDataSource(
            String url,
            String username,
            String password
    ) {

        if (dataSourceRegistry != null) return dataSourceRegistry.get(url, username, password);
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
