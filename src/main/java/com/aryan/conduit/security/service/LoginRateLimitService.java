package com.aryan.conduit.security.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class LoginRateLimitService {
    private static final int MAX_PER_IP = 60;
    private static final int MAX_PER_IP_AND_ACCOUNT = 8;
    private final JdbcTemplate jdbc;
    private final SecretKey conduitJwtKey;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(String normalizedEmail, String sourceIp) {
        String ip = sourceIp == null || sourceIp.isBlank() ? "unknown" : sourceIp;
        int ipAttempts = increment(key("ip\u0000" + ip));
        int accountAttempts = increment(key("account\u0000" + ip + "\u0000" + (normalizedEmail == null ? "" : normalizedEmail)));
        return ipAttempts <= MAX_PER_IP && accountAttempts <= MAX_PER_IP_AND_ACCOUNT;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clear(String normalizedEmail, String sourceIp) {
        String ip = sourceIp == null || sourceIp.isBlank() ? "unknown" : sourceIp;
        jdbc.update("delete from conduit_login_rate_limits where bucket_key = ?",
                key("account\u0000" + ip + "\u0000" + (normalizedEmail == null ? "" : normalizedEmail)));
    }

    private int increment(String bucketKey) {
        Integer count = jdbc.queryForObject("""
                INSERT INTO conduit_login_rate_limits AS bucket (bucket_key, window_started_at, attempt_count)
                VALUES (?, CURRENT_TIMESTAMP, 1)
                ON CONFLICT (bucket_key) DO UPDATE SET
                  window_started_at = CASE WHEN bucket.window_started_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes' THEN CURRENT_TIMESTAMP ELSE bucket.window_started_at END,
                  attempt_count = CASE WHEN bucket.window_started_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes' THEN 1 ELSE bucket.attempt_count + 1 END
                RETURNING attempt_count
                """, Integer.class, bucketKey);
        return count == null ? Integer.MAX_VALUE : count;
    }

    private String key(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(conduitJwtKey.getEncoded(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("Unable to derive login rate-limit key", error); }
    }

    @Scheduled(cron = "0 17 * * * *")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purgeOldBuckets() {
        jdbc.update("delete from conduit_login_rate_limits where window_started_at < ?", LocalDateTime.now().minusDays(1));
    }
}
