package com.aryan.conduit.execution.entity;


import com.aryan.conduit.workflow.entity.TaskNode;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name="task_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name="workflow_execution_id")
    private WorkflowExecution workflowExecution;

    @ManyToOne
    @JoinColumn(name="task_node_id")
    private TaskNode taskNode;

    @Enumerated(EnumType.STRING)
    private TaskExecutionStatus status;

    private Integer retryCount;
}
