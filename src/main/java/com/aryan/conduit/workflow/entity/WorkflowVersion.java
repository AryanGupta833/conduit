package com.aryan.conduit.workflow.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.boot.autoconfigure.web.WebProperties;

import java.time.LocalDateTime;

@Entity
@Table(name="workflow_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn
    private Workflow workflow;

    private Integer versionNumber;

    @Builder.Default
    private Boolean published=false;
    @Builder.Default
    private Boolean latest=false;

    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
}
