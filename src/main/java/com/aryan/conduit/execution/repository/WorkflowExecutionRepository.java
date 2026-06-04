package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution,Long> {
    List<WorkflowExecution> findAllByOrderByIdDesc();
}
