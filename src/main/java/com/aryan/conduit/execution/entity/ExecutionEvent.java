package com.aryan.conduit.execution.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "execution_events", indexes = {
        @Index(name = "idx_execution_events_execution_time", columnList = "workflow_execution_id, occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_execution_id", nullable = false)
    private WorkflowExecution workflowExecution;

    @Column(name = "task_execution_id")
    private Long taskExecutionId;

    @Column(nullable = false, length = 80)
    private String type;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(length = 2000)
    private String detail;
}
