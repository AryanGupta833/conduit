package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WorkflowExecution e where e.id = :id")
    java.util.Optional<WorkflowExecution> findByIdForUpdate(@Param("id") Long id);

    @Query("select count(e) from WorkflowExecution e where e.workflowVersion.workflow.id = :workflowId and e.status in :statuses")
    long countActiveByWorkflow(@Param("workflowId") Long workflowId,
                               @Param("statuses") List<WorkflowExecutionStatus> statuses);

    @Query("select count(e) from WorkflowExecution e where e.workflowVersion.workflow.owner.id = :ownerId and e.status in :statuses")
    long countActiveByOwner(@Param("ownerId") Long ownerId,
                            @Param("statuses") List<WorkflowExecutionStatus> statuses);

    List<WorkflowExecution> findByStatusInAndDeadlineAtBefore(List<WorkflowExecutionStatus> statuses,
                                                               java.time.LocalDateTime deadline);

    List<WorkflowExecution> findAllByOrderByIdDesc();
    List<WorkflowExecution> findByWorkflowVersion_Workflow_Owner_IdOrderByIdDesc(Long ownerId);
    List<WorkflowExecution> findByStatus(WorkflowExecutionStatus status);
    long countByStatus(WorkflowExecutionStatus status);
    boolean existsByWorkflowVersion_Workflow_IdAndStatus(Long workflowId,WorkflowExecutionStatus status);
    List<WorkflowExecution> findByWorkflowVersion_IdOrderByIdDesc(Long workflowVersionId);
    List<WorkflowExecution> findByWorkflowVersion_Workflow_IdOrderByIdDesc(Long workflowId);
}
