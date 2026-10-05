package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.WorkflowCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WorkflowCredentialRepository extends JpaRepository<WorkflowCredential, Long> {
    List<WorkflowCredential> findByWorkflow_IdOrderByNameAsc(Long workflowId);
    Optional<WorkflowCredential> findByWorkflow_IdAndName(Long workflowId, String name);
    Optional<WorkflowCredential> findByIdAndWorkflow_Id(Long id, Long workflowId);
}
