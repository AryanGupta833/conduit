package com.aryan.conduit.execution.entity;

import com.aryan.conduit.workflow.entity.Workflow;
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


}
