package com.aryan.conduit.security.service;

import com.aryan.conduit.security.dto.AuthUserResponse;
import com.aryan.conduit.security.dto.ChangePasswordRequest;
import com.aryan.conduit.security.dto.CreateAccountRequest;
import com.aryan.conduit.security.dto.UpdateAccountRequest;
import com.aryan.conduit.security.entity.AccountRole;
import com.aryan.conduit.security.entity.ConduitUser;
import com.aryan.conduit.security.repository.ConduitUserRepository;
import com.aryan.conduit.security.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UserAdministrationService {
    private final ConduitUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final SecurityAuditService audit;
    private final WorkflowOwnershipService workflowOwnership;

    public void assignUnownedWorkflows(Long ownerId) { workflowOwnership.assignUnownedTo(ownerId); }

    @Transactional(readOnly = true)
    public List<AuthUserResponse> list() { return users.findAllByOrderByEmailAsc().stream().map(this::response).toList(); }

    @Transactional
    public AuthUserResponse create(CreateAccountRequest request, String actor, String ip) {
        validatePassword(request.password());
        String email = normalize(request.email());
        if (users.findByEmailIgnoreCase(email).isPresent()) throw new IllegalArgumentException("An account already exists for this email");
        LocalDateTime now = LocalDateTime.now();
        ConduitUser user = users.save(ConduitUser.builder().email(email).passwordHash(passwords.encode(request.password()))
                .role(request.role()).enabled(true).failedLoginAttempts(0).tokenVersion(0).createdAt(now).updatedAt(now).build());
        audit.record("ACCOUNT_CREATED", actor, email, ip);
        return response(user);
    }

    @Transactional
    public AuthUserResponse update(Long id, UpdateAccountRequest request, String actor, String ip) {
        // Serialize admin-roster changes so concurrent requests cannot remove the last enabled admin.
        users.lockEnabledAdministrators();
        ConduitUser user = users.findById(id).orElseThrow(() -> new IllegalArgumentException("Account not found"));
        boolean losingAdmin = user.getRole() == AccountRole.ADMIN && (request.role() != AccountRole.ADMIN || !request.enabled());
        if (losingAdmin && users.countByRoleAndEnabledTrue(AccountRole.ADMIN) <= 1)
            throw new IllegalStateException("The last enabled administrator cannot be demoted or disabled");
        user.setRole(request.role()); user.setEnabled(request.enabled()); user.setUpdatedAt(LocalDateTime.now());
        user.setTokenVersion(user.getTokenVersion() + 1);
        refreshTokens.findByUser_IdAndRevokedAtIsNull(id).forEach(token -> token.setRevokedAt(LocalDateTime.now()));
        audit.record("ACCOUNT_UPDATED", actor, user.getEmail() + ":" + user.getRole() + ":" + user.isEnabled(), ip);
        return response(users.save(user));
    }

    @Transactional
    public void adminResetPassword(Long id, String password, String actor, String ip) {
        validatePassword(password);
        ConduitUser user = users.findById(id).orElseThrow(() -> new IllegalArgumentException("Account not found"));
        user.setPasswordHash(passwords.encode(password)); user.setTokenVersion(user.getTokenVersion() + 1); user.setUpdatedAt(LocalDateTime.now());
        refreshTokens.findByUser_IdAndRevokedAtIsNull(id).forEach(token -> token.setRevokedAt(LocalDateTime.now()));
        users.save(user); audit.record("ACCOUNT_PASSWORD_RESET", actor, user.getEmail(), ip);
    }

    @Transactional
    public void changePassword(String email, ChangePasswordRequest request, String ip) {
        ConduitUser user = users.findByEmailForUpdate(email).orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (!passwords.matches(request.currentPassword(), user.getPasswordHash())) throw new IllegalArgumentException("Current password is incorrect");
        validatePassword(request.newPassword());
        user.setPasswordHash(passwords.encode(request.newPassword())); user.setTokenVersion(user.getTokenVersion() + 1); user.setUpdatedAt(LocalDateTime.now());
        refreshTokens.findByUser_IdAndRevokedAtIsNull(user.getId()).forEach(token -> token.setRevokedAt(LocalDateTime.now()));
        users.save(user); audit.record("ACCOUNT_PASSWORD_CHANGED", email, email, ip);
    }

    public void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
                || password.chars().filter(Character::isLetter).count() < 1
                || password.chars().filter(Character::isDigit).count() < 1)
            throw new IllegalArgumentException("Passwords must be at least 12 characters, at most 72 UTF-8 bytes, and contain at least one letter and one number");
    }

    private String normalize(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }
    private AuthUserResponse response(ConduitUser user) { return new AuthUserResponse(user.getId(), user.getEmail(), user.getRole(), user.isEnabled(), user.isMfaEnabled()); }
}
