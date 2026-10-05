package com.aryan.conduit.execution.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "human_task_requests", indexes = {
        @Index(name = "idx_human_task_status", columnList = "status"),
        @Index(name = "idx_human_task_assignee", columnList = "assignee"),
        @Index(name = "idx_human_task_workflow", columnList = "workflow_execution_id"),
        @Index(name = "idx_human_task_execution", columnList = "task_execution_id"),
        @Index(name = "idx_human_task_expires", columnList = "expires_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HumanTaskRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "task_execution_id", nullable = false, unique = true) private TaskExecution taskExecution;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "workflow_execution_id", nullable = false) private WorkflowExecution workflowExecution;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private HumanTaskStatus status;
    @Column(nullable = false, length = 32) private String type;
    @Column(nullable = false, length = 300) private String title;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(length = 200) private String assignee;
    @Column(name = "request_payload", columnDefinition = "TEXT") private String requestPayload;
    @Column(name = "response_payload", columnDefinition = "TEXT") private String responsePayload;
    @Column(name = "created_by", length = 200) private String createdBy;
    @Column(name = "completed_by", length = 200) private String completedBy;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "expires_at") private LocalDateTime expiresAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @Column(name = "timeout_policy", nullable = false, length = 16) private String timeoutPolicy;
    @Column(name = "reminder_at") private LocalDateTime reminderAt;
    @Column(name = "reminder_sent", nullable = false) @Builder.Default private boolean reminderSent = false;
    @Column(name = "escalation_at") private LocalDateTime escalationAt;
    @Column(name = "escalation_assignee", length = 200) private String escalationAssignee;
    @Column(name = "escalated", nullable = false) @Builder.Default private boolean escalated = false;
}
