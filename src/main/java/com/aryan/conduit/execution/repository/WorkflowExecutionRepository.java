package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution,Long> {
    List<WorkflowExecution> findAllByOrderByIdDesc();
    boolean existsByWorkflow_IdAndStatus(Long workflowId, WorkflowExecutionStatus status);
    List<WorkflowExecution> findByStatus(WorkflowExecutionStatus status);
    boolean existsByWorkflowVersion_Workflow_IdAndStatus(Long workflowId,WorkflowExecutionStatus status);
    List<WorkflowExecution> findByWorkflowVersion_IdOrderByIdDesc(Long workflowVersionId);
    List<WorkflowExecution> findByWorkflowVersion_Workflow_IdOrderByIdDesc(Long workflowId);
}
