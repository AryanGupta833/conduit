package com.aryan.conduit.workflow.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "workflow_environment_profiles", uniqueConstraints = @UniqueConstraint(name = "uk_environment_profile_workflow_name", columnNames = {"workflow_id", "name"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowEnvironmentProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "workflow_id", nullable = false) private Workflow workflow;
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "values_json", nullable = false, columnDefinition = "TEXT") private String valuesJson;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
}
