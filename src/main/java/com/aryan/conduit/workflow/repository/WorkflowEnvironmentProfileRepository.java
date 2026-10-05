package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.WorkflowEnvironmentProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowEnvironmentProfileRepository extends JpaRepository<WorkflowEnvironmentProfile, Long> {
    List<WorkflowEnvironmentProfile> findByWorkflow_IdOrderByNameAsc(Long workflowId);
    Optional<WorkflowEnvironmentProfile> findByIdAndWorkflow_Id(Long id, Long workflowId);
}
