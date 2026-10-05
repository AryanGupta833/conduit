package com.aryan.conduit.security.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "conduit_users", uniqueConstraints = @UniqueConstraint(name = "uk_conduit_user_email", columnNames = "email"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConduitUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 254) private String email;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private AccountRole role;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "failed_login_attempts", nullable = false) private int failedLoginAttempts;
    @Column(name = "locked_until") private LocalDateTime lockedUntil;
    @Column(name = "token_version", nullable = false) private int tokenVersion;
    @JsonIgnore @Column(name = "totp_secret_ciphertext") private String totpSecretCiphertext;
    @JsonIgnore @Column(name = "pending_totp_secret_ciphertext") private String pendingTotpSecretCiphertext;
    @JsonIgnore @Column(name = "last_totp_counter") private Long lastTotpCounter;
    @Builder.Default @Column(name = "mfa_enabled", nullable = false) private boolean mfaEnabled = false;
    @Builder.Default @JsonIgnore @Column(name = "recovery_codes_json", nullable = false, columnDefinition = "TEXT") private String recoveryCodesJson = "[]";
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
}
