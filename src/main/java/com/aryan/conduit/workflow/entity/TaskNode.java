package com.aryan.conduit.workflow.entity;


import com.aryan.conduit.execution.entity.JoinCondition;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name="task_nodes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskNode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @ManyToOne
    @JoinColumn
    private Workflow workflow;
    private Integer timeoutSeconds;
    private Integer maxRetries;
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private JoinCondition joinCondition=JoinCondition.ALL_PARENTS;



}
