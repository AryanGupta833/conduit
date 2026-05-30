package com.aryan.conduit.execution.entity;

import com.aryan.conduit.workflow.entity.Workflow;
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
    @JoinColumn
    private Workflow workflow;

    @Enumerated(EnumType.STRING)
    private WorkflowExecutionStatus status;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
