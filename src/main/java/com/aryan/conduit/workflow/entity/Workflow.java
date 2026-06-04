package com.aryan.conduit.workflow.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name="workflows")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Workflow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Enumerated(EnumType.STRING)
    private WorkflowStatus status;
    private LocalDateTime createdAt;
    private String cronExpression;
    private Boolean active;
    private LocalDateTime lastScheduledRun;
}
