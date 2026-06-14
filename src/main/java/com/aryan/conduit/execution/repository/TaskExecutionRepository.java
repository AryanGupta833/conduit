package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.TaskExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskExecutionRepository extends JpaRepository<TaskExecution,Long> {
    List<TaskExecution> findByWorkflowExecution_Id(Long id);
    Optional<TaskExecution> findByWorkflowExecution_IdAndTaskNode_Id(Long workflowExecutionId,Long taskId);
}
