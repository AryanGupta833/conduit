package com.aryan.conduit.plugin;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reuses a bounded set of Lettuce factories instead of opening one per plugin call. */
@Component
public class RedisConnectionRegistry {
    private final int maximumFactories;
    private final Map<String, LettuceConnectionFactory> factories = new LinkedHashMap<>(16, .75f, true);

    public RedisConnectionRegistry(@Value("${conduit.plugins.redis.max-factories:8}") int maximumFactories) {
        if (maximumFactories < 1) throw new IllegalArgumentException("Redis factory limit must be positive");
        this.maximumFactories = maximumFactories;
    }

    public synchronized LettuceConnectionFactory get(String host, int port, String password, int database, int timeoutSeconds) {
        String id = hash(host + "\u0000" + port + "\u0000" + password + "\u0000" + database + "\u0000" + timeoutSeconds);
        var existing = factories.get(id);
        if (existing != null) return existing;
        if (factories.size() >= maximumFactories) throw new IllegalStateException("Redis plugin connection capacity is exhausted");
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration(host, port);
        redis.setDatabase(database);
        if (password != null && !password.isBlank()) redis.setPassword(password);
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(timeoutSeconds)).shutdownTimeout(Duration.ofSeconds(2)).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis, client);
        factory.afterPropertiesSet();
        factory.start();
        factories.put(id, factory);
        return factory;
    }

    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Unable to identify Redis connection", e); }
    }

    @PreDestroy public synchronized void close() { factories.values().forEach(LettuceConnectionFactory::destroy); factories.clear(); }
}
