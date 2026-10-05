package com.aryan.conduit.execution.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "execution_notifications", indexes = @Index(name = "idx_execution_notification_created", columnList = "created_at"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExecutionNotification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_key", nullable = false, unique = true, length = 160) private String eventKey;
    @Column(nullable = false, length = 40) private String type;
    @Column(nullable = false, length = 300) private String title;
    @Column(columnDefinition = "TEXT") private String message;
    @Column(name = "workflow_execution_id", nullable = false) private Long workflowExecutionId;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "read_at") private LocalDateTime readAt;
}
