package com.aryan.conduit.security.service;

import com.aryan.conduit.security.entity.SecurityAuditEvent;
import com.aryan.conduit.security.repository.SecurityAuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SecurityAuditService {
    private final SecurityAuditEventRepository events;
    public void record(String type, String actor, String target, String ip) {
        events.save(SecurityAuditEvent.builder().eventType(type).actor(limit(actor == null || actor.isBlank() ? "system" : actor, 254)).target(limit(target, 254))
                .sourceIp(limit(ip, 64)).createdAt(LocalDateTime.now()).build());
    }
    private String limit(String value, int size) { return value == null ? null : value.substring(0, Math.min(value.length(), size)); }
}
