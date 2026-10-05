package com.aryan.conduit.security.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "security_audit_events", indexes = {
        @Index(name = "idx_security_audit_created", columnList = "created_at"),
        @Index(name = "idx_security_audit_actor", columnList = "actor")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SecurityAuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_type", nullable = false, length = 64) private String eventType;
    @Column(nullable = false, length = 254) private String actor;
    @Column(length = 254) private String target;
    @Column(name = "source_ip", length = 64) private String sourceIp;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
}
