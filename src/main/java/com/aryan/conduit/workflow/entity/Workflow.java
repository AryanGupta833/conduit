package com.aryan.conduit.workflow.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

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
    @Column(name = "environment_profile_id")
    private Long environmentProfileId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    @JsonIgnore
    private com.aryan.conduit.security.entity.ConduitUser owner;
    private LocalDateTime lastScheduledRun;
    @Column(name = "max_concurrent_executions")
    private Integer maxConcurrentExecutions;
    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 0;
    @OneToMany(mappedBy = "workflow",cascade = CascadeType.ALL)
    @JsonIgnore
    private List<WorkflowVersion> versions;

    @PrePersist
    private void applyDefaults() {
        if (priority == null) priority = 0;
    }


}
