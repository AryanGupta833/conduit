package com.aryan.conduit.security.controller;

import com.aryan.conduit.security.dto.*;
import com.aryan.conduit.security.repository.ConduitUserRepository;
import com.aryan.conduit.security.repository.SecurityAuditEventRepository;
import com.aryan.conduit.security.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthenticationController {
    private final AuthenticationService authentication;
    private final UserAdministrationService accounts;
    private final ConduitUserRepository users;
    private final SecurityAuditEventRepository auditEvents;
    private final RefreshCookieService cookies;
    private final MfaService mfa;
    @Value("${conduit.security.allowed-origins}") private String allowedOrigins;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http, HttpServletResponse response) {
        try {
            requireAllowedOrigin(http);
            var pair = authentication.login(request.email(), request.password(), request.totpCode(), http.getRemoteAddr());
            cookies.set(response, pair.refreshToken());
            return ResponseEntity.ok(pair.response());
        } catch (AuthenticationRejectedException rejected) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Email or password is incorrect, or this account is unavailable"));
        } catch (SecurityException rejectedOrigin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Request origin is not allowed"));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = RefreshCookieService.COOKIE_NAME, required = false) String token,
                                     HttpServletRequest http, HttpServletResponse response) {
        try {
            requireAllowedOrigin(http);
            var pair = authentication.refresh(token, http.getRemoteAddr());
            cookies.set(response, pair.refreshToken());
            return ResponseEntity.ok(pair.response());
        } catch (RefreshTokenRejectedException rejected) {
            cookies.clear(response);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Sign-in expired. Please sign in again."));
        } catch (SecurityException rejectedOrigin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Request origin is not allowed"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = RefreshCookieService.COOKIE_NAME, required = false) String token,
                                       HttpServletRequest http, HttpServletResponse response) {
        try { requireAllowedOrigin(http); }
        catch (SecurityException rejected) { return ResponseEntity.status(HttpStatus.FORBIDDEN).build(); }
        authentication.logout(token, http.getRemoteAddr()); cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(Authentication principal, HttpServletRequest http, HttpServletResponse response) {
        authentication.logoutEverywhere(principal.getName(), http.getRemoteAddr()); cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthUserResponse me(Authentication principal) {
        var user = users.findByEmailIgnoreCase(principal.getName()).orElseThrow();
        return new AuthUserResponse(user.getId(), user.getEmail(), user.getRole(), user.isEnabled(), user.isMfaEnabled());
    }

    @PostMapping("/mfa/enroll")
    public MfaEnrollmentResponse startMfa(Authentication principal, @Valid @RequestBody MfaStartRequest request) {
        return mfa.startEnrollment(principal.getName(), request.password());
    }

    @PostMapping("/mfa/confirm")
    public MfaRecoveryCodesResponse confirmMfa(Authentication principal, @Valid @RequestBody MfaCodeRequest request) {
        return mfa.confirmEnrollment(principal.getName(), request.code());
    }

    @PostMapping("/mfa/disable")
    public ResponseEntity<Void> disableMfa(Authentication principal, @Valid @RequestBody MfaDisableRequest request) {
        mfa.disable(principal.getName(), request.password(), request.code());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(Authentication principal, @Valid @RequestBody ChangePasswordRequest request,
                                               HttpServletRequest http, HttpServletResponse response) {
        accounts.changePassword(principal.getName(), request, http.getRemoteAddr()); cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users")
    public List<AuthUserResponse> listUsers() { return accounts.list(); }

    @GetMapping("/audit")
    public List<com.aryan.conduit.security.entity.SecurityAuditEvent> audit() {
        return auditEvents.findTop200ByOrderByCreatedAtDesc();
    }

    @PostMapping("/users")
    public AuthUserResponse createUser(Authentication principal, @Valid @RequestBody CreateAccountRequest request, HttpServletRequest http) {
        return accounts.create(request, principal.getName(), http.getRemoteAddr());
    }

    @PutMapping("/users/{id}")
    public AuthUserResponse updateUser(Authentication principal, @PathVariable Long id,
                                       @Valid @RequestBody UpdateAccountRequest request, HttpServletRequest http) {
        return accounts.update(id, request, principal.getName(), http.getRemoteAddr());
    }

    @PutMapping("/users/{id}/password")
    public ResponseEntity<Void> resetPassword(Authentication principal, @PathVariable Long id,
                                               @Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        accounts.adminResetPassword(id, request.newPassword(), principal.getName(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{id}/mfa")
    public ResponseEntity<Void> resetMfa(Authentication principal, @PathVariable Long id) {
        mfa.adminReset(id, principal.getName());
        return ResponseEntity.noContent().build();
    }

    private void requireAllowedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return;
        if (java.util.Arrays.stream(allowedOrigins.split(",")).map(String::trim).noneMatch(origin::equals))
            throw new SecurityException("Origin rejected");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidAccountRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage() == null ? "Invalid request" : error.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> accountConflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", error.getMessage() == null ? "Account operation conflict" : error.getMessage()));
    }
}
