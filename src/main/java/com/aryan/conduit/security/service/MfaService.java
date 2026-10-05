package com.aryan.conduit.security.service;

import com.aryan.conduit.security.dto.MfaEnrollmentResponse;
import com.aryan.conduit.security.dto.MfaRecoveryCodesResponse;
import com.aryan.conduit.security.entity.ConduitUser;
import com.aryan.conduit.security.repository.ConduitUserRepository;
import com.aryan.conduit.security.repository.RefreshTokenRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MfaService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final ConduitUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final SecurityAuditService audit;
    private final Environment environment;
    private final ObjectMapper mapper;

    @Transactional
    public MfaEnrollmentResponse startEnrollment(String email, String password) {
        ConduitUser user = users.findByEmailForUpdate(email).orElseThrow(AuthenticationRejectedException::new);
        if (!passwords.matches(password, user.getPasswordHash())) throw new AuthenticationRejectedException();
        if (user.isMfaEnabled()) throw new IllegalStateException("Multi-factor authentication is already enabled");
        byte[] random = new byte[20]; RANDOM.nextBytes(random);
        String secret = encodeBase32(random);
        user.setPendingTotpSecretCiphertext(encrypt(secret));
        users.save(user);
        String label = URLEncoder.encode("Conduit:" + user.getEmail(), StandardCharsets.UTF_8).replace("+", "%20");
        String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=Conduit&algorithm=SHA1&digits=6&period=30";
        audit.record("MFA_ENROLLMENT_STARTED", email, null, null);
        return new MfaEnrollmentResponse(secret, uri);
    }

    @Transactional
    public MfaRecoveryCodesResponse confirmEnrollment(String email, String code) {
        ConduitUser user = users.findByEmailForUpdate(email).orElseThrow(AuthenticationRejectedException::new);
        if (user.isMfaEnabled() || user.getPendingTotpSecretCiphertext() == null)
            throw new IllegalStateException("Start multi-factor enrollment before confirming it");
        String secret = decrypt(user.getPendingTotpSecretCiphertext());
        Long matchedCounter = matchingTotpCounter(secret, code);
        if (matchedCounter == null) throw new AuthenticationRejectedException();
        List<String> recoveryCodes = new ArrayList<>();
        List<String> recoveryHashes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            byte[] bytes = new byte[6]; RANDOM.nextBytes(bytes);
            String raw = HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
            String display = raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8);
            recoveryCodes.add(display);
            recoveryHashes.add(hashRecovery(display));
        }
        user.setTotpSecretCiphertext(user.getPendingTotpSecretCiphertext());
        user.setPendingTotpSecretCiphertext(null);
        user.setLastTotpCounter(matchedCounter);
        user.setRecoveryCodesJson(write(recoveryHashes));
        user.setMfaEnabled(true);
        invalidateSessions(user);
        users.save(user);
        audit.record("MFA_ENABLED", email, null, null);
        return new MfaRecoveryCodesResponse(List.copyOf(recoveryCodes), true);
    }

    @Transactional
    public void disable(String email, String password, String code) {
        ConduitUser user = users.findByEmailForUpdate(email).orElseThrow(AuthenticationRejectedException::new);
        if (!user.isMfaEnabled() || !passwords.matches(password, user.getPasswordHash()) || !verifyAndConsume(user, code))
            throw new AuthenticationRejectedException();
        user.setMfaEnabled(false);
        user.setTotpSecretCiphertext(null);
        user.setPendingTotpSecretCiphertext(null);
        user.setLastTotpCounter(null);
        user.setRecoveryCodesJson("[]");
        invalidateSessions(user);
        users.save(user);
        audit.record("MFA_DISABLED", email, null, null);
    }

    @Transactional
    public void adminReset(Long id, String actor) {
        ConduitUser user = users.findById(id).orElseThrow(() -> new IllegalArgumentException("Account not found"));
        user.setMfaEnabled(false);
        user.setTotpSecretCiphertext(null);
        user.setPendingTotpSecretCiphertext(null);
        user.setLastTotpCounter(null);
        user.setRecoveryCodesJson("[]");
        invalidateSessions(user);
        users.save(user);
        audit.record("MFA_ADMIN_RESET", actor, user.getEmail(), null);
    }

    /** Called while AuthenticationService holds the user's database row lock. */
    @Transactional
    public boolean verifyLogin(ConduitUser user, String code) {
        if (!user.isMfaEnabled()) return true;
        return verifyAndConsume(user, code);
    }

    private boolean verifyAndConsume(ConduitUser user, String code) {
        if (code == null || code.isBlank()) return false;
        Long matchedCounter = matchingTotpCounter(decrypt(user.getTotpSecretCiphertext()), code);
        if (matchedCounter != null && (user.getLastTotpCounter() == null || matchedCounter > user.getLastTotpCounter())) {
            user.setLastTotpCounter(matchedCounter);
            users.save(user);
            return true;
        }
        String expected = hashRecovery(code);
        List<String> hashes = readRecoveryHashes(user.getRecoveryCodesJson());
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < hashes.size(); i++) {
            if (MessageDigest.isEqual(expectedBytes, hashes.get(i).getBytes(StandardCharsets.US_ASCII))) {
                hashes.remove(i);
                user.setRecoveryCodesJson(write(hashes));
                users.save(user);
                audit.record("MFA_RECOVERY_CODE_USED", user.getEmail(), null, null);
                return true;
            }
        }
        return false;
    }

    private void invalidateSessions(ConduitUser user) {
        user.setTokenVersion(user.getTokenVersion() + 1);
        refreshTokens.findByUser_IdAndRevokedAtIsNull(user.getId()).forEach(token -> token.setRevokedAt(LocalDateTime.now()));
    }

    private Long matchingTotpCounter(String secret, String supplied) {
        if (supplied == null || !supplied.matches("[0-9]{6}")) return null;
        long counter = Instant.now().getEpochSecond() / 30;
        byte[] suppliedBytes = supplied.getBytes(StandardCharsets.US_ASCII);
        Long matched = null;
        for (long delta = -1; delta <= 1; delta++) {
            long candidate = counter + delta;
            byte[] expected = totp(secret, candidate).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, suppliedBytes)) matched = candidate;
        }
        return matched;
    }

    private String totp(String secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (Exception error) { throw new IllegalStateException("Unable to verify authenticator code", error); }
    }

    private byte[] encryptionKey() {
        try {
            byte[] key = Base64.getDecoder().decode(environment.getProperty("conduit.security.mfa-encryption-key", ""));
            if (key.length != 32) throw new IllegalArgumentException();
            return key;
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Set CONDUIT_MFA_ENCRYPTION_KEY to a base64-encoded random 32-byte key before enabling MFA");
        }
    }

    private String encrypt(String value) {
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey(), "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length); System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception error) { throw new IllegalStateException("Unable to protect authenticator secret", error); }
    }

    private String decrypt(String value) {
        try {
            byte[] payload = Base64.getDecoder().decode(value);
            byte[] iv = Arrays.copyOfRange(payload, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey(), "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("Unable to decrypt authenticator secret; verify CONDUIT_MFA_ENCRYPTION_KEY", error); }
    }

    private static String encodeBase32(byte[] bytes) {
        StringBuilder output = new StringBuilder(); int buffer = 0, bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff); bits += 8;
            while (bits >= 5) { output.append(BASE32.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) output.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return output.toString();
    }

    private static byte[] decodeBase32(String value) {
        String normalized = value.replace("=", "").toUpperCase(Locale.ROOT);
        byte[] output = new byte[normalized.length() * 5 / 8]; int buffer = 0, bits = 0, index = 0;
        for (char symbol : normalized.toCharArray()) {
            int digit = BASE32.indexOf(symbol); if (digit < 0) throw new IllegalArgumentException("Invalid TOTP secret");
            buffer = (buffer << 5) | digit; bits += 5;
            if (bits >= 8) { output[index++] = (byte) ((buffer >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return output;
    }

    private static String hashRecovery(String code) {
        String normalized = code == null ? "" : code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.US_ASCII))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private List<String> readRecoveryHashes(String json) {
        try { return new ArrayList<>(mapper.readValue(json == null ? "[]" : json, new TypeReference<>() { })); }
        catch (Exception error) { throw new IllegalStateException("MFA recovery data is unreadable", error); }
    }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Unable to persist MFA recovery codes", error); }
    }
}
