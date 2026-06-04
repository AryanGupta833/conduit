package com.aryan.conduit.execution.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name="execution_contexts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@RequiredArgsConstructor
@Builder
public class ExecutionContext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    private WorkflowExecution workflowExecution;

    @Lob
    private String variableJson;
}
