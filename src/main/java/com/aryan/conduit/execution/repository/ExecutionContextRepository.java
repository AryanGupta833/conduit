package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.ExecutionContextEntity;
import com.aryan.conduit.execution.entity.ExecutionContextEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface ExecutionContextRepository extends JpaRepository<ExecutionContextEntity,Long> {
    Optional<ExecutionContextEntity> findByWorkflowExecution_Id(Long workflowExecutionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ExecutionContextEntity c where c.workflowExecution.id = :executionId")
    Optional<ExecutionContextEntity> findByWorkflowExecutionIdForUpdate(
            @Param("executionId") Long workflowExecutionId);
}
