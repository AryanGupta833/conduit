package com.aryan.conduit.workflow.entity;

import com.aryan.conduit.execution.entity.DependencyCondition;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.scheduling.config.Task;

@Entity
@Table(name="dependencies", uniqueConstraints = @UniqueConstraint(
        name = "uk_dependency_parent_child", columnNames = {"parent_task_id", "child_task_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dependency {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name="parent_task_id")
    private TaskNode parent;

    @ManyToOne
    @JoinColumn(name="child_task_id")
    private TaskNode child;

    @Enumerated(EnumType.STRING)
    private DependencyCondition condition;

    private String expression;

}
