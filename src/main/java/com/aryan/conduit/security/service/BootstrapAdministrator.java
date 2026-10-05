package com.aryan.conduit.security.service;

import com.aryan.conduit.security.entity.AccountRole;
import com.aryan.conduit.security.entity.ConduitUser;
import com.aryan.conduit.security.repository.ConduitUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class BootstrapAdministrator implements ApplicationRunner {
    private final ConduitUserRepository users;
    private final PasswordEncoder passwords;
    private final UserAdministrationService userAdministration;
    @Value("${conduit.security.bootstrap-admin-email:}") private String bootstrapEmail;
    @Value("${conduit.security.bootstrap-admin-password:}") private String bootstrapPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() == 0) {
            if (bootstrapEmail == null || bootstrapEmail.isBlank() || bootstrapPassword == null || bootstrapPassword.isBlank())
                throw new IllegalStateException("No Conduit accounts exist. Configure CONDUIT_BOOTSTRAP_ADMIN_EMAIL and CONDUIT_BOOTSTRAP_ADMIN_PASSWORD for first startup.");
            userAdministration.validatePassword(bootstrapPassword);
            LocalDateTime now = LocalDateTime.now();
            users.save(ConduitUser.builder().email(bootstrapEmail.trim().toLowerCase(Locale.ROOT)).passwordHash(passwords.encode(bootstrapPassword))
                    .role(AccountRole.ADMIN).enabled(true).failedLoginAttempts(0).tokenVersion(0).mfaEnabled(false).recoveryCodesJson("[]").createdAt(now).updatedAt(now).build());
        }
        users.findFirstByRoleAndEnabledTrueOrderByIdAsc(AccountRole.ADMIN)
                .ifPresent(admin -> userAdministration.assignUnownedWorkflows(admin.getId()));
    }
}
