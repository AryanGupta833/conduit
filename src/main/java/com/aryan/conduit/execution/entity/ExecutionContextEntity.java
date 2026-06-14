package com.aryan.conduit.execution.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name="execution_contexts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionContextEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name="workflow_execution_id")
    private WorkflowExecution workflowExecution;

    @Column(columnDefinition = "TEXT")
    private String variableJson;
}
