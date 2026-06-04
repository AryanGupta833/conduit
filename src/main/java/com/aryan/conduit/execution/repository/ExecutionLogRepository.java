package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.ExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionLogRepository extends JpaRepository<ExecutionLog,Long> {
    List<ExecutionLog> findByTaskExecution_Id(Long taskExecutionId);
    List<ExecutionLog> findByTaskExecution_WorkflowExecution_Id(Long workflowExecutionId);

}
