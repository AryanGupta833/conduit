package com.aryan.conduit.trigger;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "workflow_trigger_inbox")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowTriggerInbox {
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private Long workflowId;

    @jakarta.persistence.ManyToOne
    @jakarta.persistence.JoinColumn(name = "execution_id")
    private WorkflowExecution workflowExecution;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getExecutionId() {
        return workflowExecution == null ? null : workflowExecution.getId();
    }
}
