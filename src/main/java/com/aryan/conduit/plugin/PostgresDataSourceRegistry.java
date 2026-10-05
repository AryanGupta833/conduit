package com.aryan.conduit.plugin;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded shared pools for workflow supplied PostgreSQL endpoints. */
@Component
public class PostgresDataSourceRegistry {
    private final int maximumPools;
    private final int maximumPoolSize;
    private final long connectionTimeoutMs;
    private final Map<String, HikariDataSource> pools = new LinkedHashMap<>(16, .75f, true);

    public PostgresDataSourceRegistry(
            @Value("${conduit.plugins.postgresql.max-pools:8}") int maximumPools,
            @Value("${conduit.plugins.postgresql.max-pool-size:5}") int maximumPoolSize,
            @Value("${conduit.plugins.postgresql.connection-timeout-ms:5000}") long connectionTimeoutMs) {
        if (maximumPools < 1 || maximumPoolSize < 1 || connectionTimeoutMs < 250)
            throw new IllegalArgumentException("Invalid PostgreSQL plugin pool limits");
        this.maximumPools = maximumPools;
        this.maximumPoolSize = maximumPoolSize;
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public synchronized DataSource get(String url, String username, String password) {
        String key = fingerprint(url + "\u0000" + username + "\u0000" + password);
        HikariDataSource existing = pools.get(key);
        if (existing != null && !existing.isClosed()) return existing;
        if (pools.size() >= maximumPools)
            throw new IllegalStateException("PostgreSQL plugin connection pool capacity is exhausted");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("conduit-pg-" + key.substring(0, 10));
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setValidationTimeout(Math.min(2000, connectionTimeoutMs));
        config.setInitializationFailTimeout(-1);
        HikariDataSource created = new HikariDataSource(config);
        pools.put(key, created);
        return created;
    }

    private String fingerprint(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) { throw new IllegalStateException("Unable to fingerprint datasource identity", e); }
    }

    @PreDestroy public synchronized void close() { pools.values().forEach(HikariDataSource::close); pools.clear(); }
}
