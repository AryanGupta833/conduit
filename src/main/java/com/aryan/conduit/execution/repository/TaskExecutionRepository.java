package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskExecutionRepository
        extends JpaRepository<TaskExecution, Long> {

    List<TaskExecution> findByWorkflowExecution_Id(Long id);

    Optional<TaskExecution> findByWorkflowExecution_IdAndTaskNode_Id(
            Long workflowExecutionId,
            Long taskId
    );

    @Modifying
    @Query("""
            UPDATE TaskExecution t
            SET t.status = :status
            WHERE t.id = :taskExecutionId
            AND t.status = :expectedStatus
            """)
    int updateStatusIfCurrent(
            @Param("taskExecutionId") Long taskExecutionId,
            @Param("expectedStatus") TaskExecutionStatus expectedStatus,
            @Param("status") TaskExecutionStatus status
    );

    @Modifying
    @Query("""
    UPDATE TaskExecution t
    SET t.status = :queuedStatus
    WHERE t.id = :taskExecutionId
      AND t.status = :pendingStatus
""")
    int claimForQueue(
            @Param("taskExecutionId") Long taskExecutionId,
            @Param("pendingStatus") TaskExecutionStatus pendingStatus,
            @Param("queuedStatus") TaskExecutionStatus queuedStatus
    );
}