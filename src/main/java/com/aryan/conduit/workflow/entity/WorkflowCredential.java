package com.aryan.conduit.workflow.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "workflow_credentials", uniqueConstraints = @UniqueConstraint(name = "uk_workflow_credential_name", columnNames = {"workflow_id", "name"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowCredential {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "workflow_id", nullable = false) private Workflow workflow;
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "TEXT") private String secretCiphertext;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
}
