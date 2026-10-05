package com.aryan.conduit.security.repository;

import com.aryan.conduit.security.entity.SecurityAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityAuditEventRepository extends JpaRepository<SecurityAuditEvent, Long> {
    java.util.List<SecurityAuditEvent> findTop200ByOrderByCreatedAtDesc();
}
