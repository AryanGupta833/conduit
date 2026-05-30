package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution,Long> {
}
