package com.aryan.conduit.workflow.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.scheduling.config.Task;

@Entity
@Table(name="dependencies")
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

}
