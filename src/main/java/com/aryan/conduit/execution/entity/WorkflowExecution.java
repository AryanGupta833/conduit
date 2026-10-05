package com.aryan.conduit.execution.entity;

import com.aryan.conduit.workflow.entity.WorkflowVersion;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name="workflow_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "workflow_version_id")
    private WorkflowVersion workflowVersion;

    @Enumerated(EnumType.STRING)
    private WorkflowExecutionStatus status;

    private Integer versionNumber;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Column(name = "deadline_at")
    private LocalDateTime deadlineAt;

    @Column(nullable = false)
    private Integer priority;

    @Column(name = "initiated_by", length = 254)
    private String initiatedBy;

    @Column(name = "correlation_id", unique = true, length = 36)
    private String correlationId;

    @PrePersist
    void ensureCorrelationId() {
        if (correlationId == null || correlationId.isBlank())
            correlationId = java.util.UUID.randomUUID().toString();
        if (priority == null) priority = 0;
    }


}
