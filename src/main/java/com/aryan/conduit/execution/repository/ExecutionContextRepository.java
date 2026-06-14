package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.ExecutionContextEntity;
import com.aryan.conduit.execution.entity.ExecutionContextEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExecutionContextRepository extends JpaRepository<ExecutionContextEntity,Long> {
    Optional<ExecutionContextEntity> findByWorkflowExecution_Id(Long workflowExecutionId);
}
