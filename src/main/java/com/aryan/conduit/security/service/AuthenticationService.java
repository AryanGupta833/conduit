package com.aryan.conduit.security.service;

import com.aryan.conduit.security.dto.AuthSessionResponse;
import com.aryan.conduit.security.dto.AuthUserResponse;
import com.aryan.conduit.security.entity.ConduitUser;
import com.aryan.conduit.security.entity.RefreshToken;
import com.aryan.conduit.security.repository.ConduitUserRepository;
import com.aryan.conduit.security.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthenticationService {
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final Duration MAX_REFRESH_FAMILY_AGE = Duration.ofDays(90);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ConduitUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final JwtTokenService jwtTokens;
    private final SecurityAuditService audit;
    private final MfaService mfa;
    private final LoginRateLimitService loginRateLimit;
    @Value("${conduit.security.refresh-token-days:14}") private long refreshDays;
    private String dummyHash;

    @jakarta.annotation.PostConstruct
    void initializeDummyPasswordHash() { dummyHash = passwords.encode(UUID.randomUUID().toString()); }

    @Transactional(noRollbackFor = AuthenticationRejectedException.class)
    public TokenPair login(String email, String password, String totpCode, String ip) {
        String normalized = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        if (!loginRateLimit.allow(normalized, ip)) {
            throw new AuthenticationRejectedException();
        }
        ConduitUser user = users.findByEmailForUpdate(normalized).orElse(null);
        String hash = user == null ? dummyHash : user.getPasswordHash();
        boolean passwordMatches = password != null
                && password.getBytes(StandardCharsets.UTF_8).length <= 72
                && passwords.matches(password, hash);
        LocalDateTime now = LocalDateTime.now();
        boolean accountMayAuthenticate = user != null && user.isEnabled()
                && (user.getLockedUntil() == null || !user.getLockedUntil().isAfter(now));
        boolean secondFactorMatches = user == null || !user.isMfaEnabled()
                || accountMayAuthenticate && passwordMatches && mfa.verifyLogin(user, totpCode);
        if (user == null || !user.isEnabled() || user.getLockedUntil() != null && user.getLockedUntil().isAfter(now) || !passwordMatches || !secondFactorMatches) {
            if (user != null && user.isEnabled()) {
                user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
                if (user.getFailedLoginAttempts() >= MAX_LOGIN_FAILURES) {
                    user.setLockedUntil(now.plus(LOCK_DURATION));
                    user.setFailedLoginAttempts(0);
                }
                user.setUpdatedAt(now);
                users.save(user);
            }
            audit.record("LOGIN_REJECTED", normalized.isEmpty() ? "unknown" : normalized, null, ip);
            throw new AuthenticationRejectedException();
        }
        user.setFailedLoginAttempts(0); user.setLockedUntil(null); user.setUpdatedAt(now);
        loginRateLimit.clear(normalized, ip);
        audit.record("LOGIN_SUCCEEDED", user.getEmail(), null, ip);
        return issuePair(user, UUID.randomUUID(), now.plus(MAX_REFRESH_FAMILY_AGE));
    }

    @Transactional(noRollbackFor = RefreshTokenRejectedException.class)
    public TokenPair refresh(String rawToken, String ip) {
        if (rawToken == null || rawToken.length() > 256) throw new RefreshTokenRejectedException();
        RefreshToken current = refreshTokens.findByTokenHashForUpdate(hash(rawToken)).orElse(null);
        if (current == null) throw new RefreshTokenRejectedException();
        if (current.getRevokedAt() != null) {
            refreshTokens.findByFamilyId(current.getFamilyId()).forEach(token -> { if (token.getRevokedAt() == null) token.setRevokedAt(LocalDateTime.now()); });
            audit.record("REFRESH_REUSE_DETECTED", current.getUser().getEmail(), current.getFamilyId().toString(), ip);
            throw new RefreshTokenRejectedException();
        }
        LocalDateTime now = LocalDateTime.now();
        if (!current.getExpiresAt().isAfter(now) || !current.getFamilyExpiresAt().isAfter(now) || !current.getUser().isEnabled()) {
            current.setRevokedAt(now); refreshTokens.save(current);
            throw new RefreshTokenRejectedException();
        }
        current.setRevokedAt(now); current.setLastUsedAt(now); refreshTokens.save(current);
        audit.record("TOKEN_REFRESHED", current.getUser().getEmail(), current.getFamilyId().toString(), ip);
        return issuePair(current.getUser(), current.getFamilyId(), current.getFamilyExpiresAt());
    }

    @Transactional
    public void logout(String rawToken, String ip) {
        if (rawToken == null || rawToken.length() > 256) return;
        refreshTokens.findByTokenHashForUpdate(hash(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) { token.setRevokedAt(LocalDateTime.now()); refreshTokens.save(token); }
            audit.record("LOGOUT", token.getUser().getEmail(), token.getFamilyId().toString(), ip);
        });
    }

    @Transactional
    public void logoutEverywhere(String email, String ip) {
        ConduitUser user = users.findByEmailForUpdate(email).orElseThrow(RefreshTokenRejectedException::new);
        refreshTokens.findByUser_IdAndRevokedAtIsNull(user.getId()).forEach(token -> token.setRevokedAt(LocalDateTime.now()));
        user.setTokenVersion(user.getTokenVersion() + 1); users.save(user);
        audit.record("LOGOUT_ALL", user.getEmail(), null, ip);
    }

    private TokenPair issuePair(ConduitUser user, UUID familyId, LocalDateTime familyExpiresAt) {
        byte[] bytes = new byte[48]; RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now();
        refreshTokens.save(RefreshToken.builder().user(user).tokenHash(hash(raw)).familyId(familyId)
                .familyExpiresAt(familyExpiresAt).expiresAt(now.plusDays(refreshDays).isBefore(familyExpiresAt) ? now.plusDays(refreshDays) : familyExpiresAt).createdAt(now).build());
        JwtTokenService.IssuedAccessToken access = jwtTokens.issue(user);
        return new TokenPair(new AuthSessionResponse(access.token(), access.expiresInSeconds(),
                new AuthUserResponse(user.getId(), user.getEmail(), user.getRole(), user.isEnabled(), user.isMfaEnabled())), raw);
    }

    public static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    @org.springframework.scheduling.annotation.Scheduled(cron = "0 22 3 * * *")
    @Transactional
    public void purgeExpiredRefreshFamilies() {
        refreshTokens.deleteByFamilyExpiresAtBefore(LocalDateTime.now().minusDays(30));
    }

    public record TokenPair(AuthSessionResponse response, String refreshToken) { }
}
