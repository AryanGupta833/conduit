package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.WorkflowVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion,Long> {
    List<WorkflowVersion> findByWorkflow_IdOrderByVersionNumberDesc(Long workflowId);
    Optional<WorkflowVersion> findByWorkflow_IdAndPublishedTrue(Long workflowId);
    Optional<WorkflowVersion> findTopByWorkflow_IdOrderByVersionNumberDesc(Long workflowId);
    Optional<WorkflowVersion> findByWorkflow_IdAndLatestTrue(Long workflowId);
}
