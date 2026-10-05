package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.HumanTaskRequest;
import com.aryan.conduit.execution.entity.HumanTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface HumanTaskRequestRepository extends JpaRepository<HumanTaskRequest, Long> {
    Optional<HumanTaskRequest> findByTaskExecution_Id(Long taskExecutionId);
    List<HumanTaskRequest> findAllByOrderByCreatedAtDesc();
    List<HumanTaskRequest> findByStatusOrderByCreatedAtDesc(HumanTaskStatus status);
    long countByStatus(HumanTaskStatus status);
    List<HumanTaskRequest> findByWorkflowExecution_IdOrderByCreatedAtDesc(Long workflowExecutionId);
    List<HumanTaskRequest> findByAssigneeIgnoreCaseOrderByCreatedAtDesc(String assignee);
    List<HumanTaskRequest> findByTypeIgnoreCaseOrderByCreatedAtDesc(String type);
    List<HumanTaskRequest> findByWorkflowExecution_IdAndStatusOrderByCreatedAtDesc(Long workflowExecutionId, HumanTaskStatus status);
    List<HumanTaskRequest> findByAssigneeIgnoreCaseAndStatusOrderByCreatedAtDesc(String assignee, HumanTaskStatus status);
    List<HumanTaskRequest> findByTypeIgnoreCaseAndStatusOrderByCreatedAtDesc(String type, HumanTaskStatus status);
    List<HumanTaskRequest> findByWorkflowExecution_IdAndAssigneeIgnoreCaseOrderByCreatedAtDesc(Long workflowExecutionId, String assignee);
    List<HumanTaskRequest> findByWorkflowExecution_IdAndTypeIgnoreCaseOrderByCreatedAtDesc(Long workflowExecutionId, String type);
    List<HumanTaskRequest> findByAssigneeIgnoreCaseAndTypeIgnoreCaseOrderByCreatedAtDesc(String assignee, String type);
    List<HumanTaskRequest> findByWorkflowExecution_IdAndAssigneeIgnoreCaseAndStatusOrderByCreatedAtDesc(Long workflowExecutionId, String assignee, HumanTaskStatus status);
    List<HumanTaskRequest> findByWorkflowExecution_IdAndTypeIgnoreCaseAndStatusOrderByCreatedAtDesc(Long workflowExecutionId, String type, HumanTaskStatus status);
    List<HumanTaskRequest> findByAssigneeIgnoreCaseAndTypeIgnoreCaseAndStatusOrderByCreatedAtDesc(String assignee, String type, HumanTaskStatus status);
    List<HumanTaskRequest> findByWorkflowExecution_IdAndAssigneeIgnoreCaseAndTypeIgnoreCaseOrderByCreatedAtDesc(Long workflowExecutionId, String assignee, String type);
    List<HumanTaskRequest> findByWorkflowExecution_IdAndAssigneeIgnoreCaseAndTypeIgnoreCaseAndStatusOrderByCreatedAtDesc(Long workflowExecutionId, String assignee, String type, HumanTaskStatus status);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HumanTaskRequest h set h.status=:next, h.responsePayload=:payload, h.completedBy=:actor, h.completedAt=:now, h.updatedAt=:now where h.id=:id and h.status=:waiting and (h.expiresAt is null or h.expiresAt>:now)")
    int complete(@Param("id") Long id, @Param("waiting") HumanTaskStatus waiting, @Param("next") HumanTaskStatus next,
                 @Param("payload") String payload, @Param("actor") String actor, @Param("now") LocalDateTime now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HumanTaskRequest h set h.status=:expired, h.responsePayload=:payload, h.completedAt=:now, h.updatedAt=:now where h.id=:id and h.status=:waiting and h.expiresAt<=:now")
    int expire(@Param("id") Long id, @Param("waiting") HumanTaskStatus waiting, @Param("expired") HumanTaskStatus expired,
               @Param("payload") String payload, @Param("now") LocalDateTime now);
    @Query("select h.id from HumanTaskRequest h where h.status=:waiting and h.expiresAt is not null and h.expiresAt<=:now order by h.expiresAt")
    List<Long> findExpiredIds(@Param("waiting") HumanTaskStatus waiting, @Param("now") LocalDateTime now);
    @Query("select h.id from HumanTaskRequest h where h.status=:waiting and h.reminderAt<=:now and h.reminderSent=false")
    List<Long> findReminderDueIds(@Param("waiting") HumanTaskStatus waiting, @Param("now") LocalDateTime now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HumanTaskRequest h set h.reminderSent=true, h.updatedAt=:now where h.id=:id and h.status=:waiting and h.reminderSent=false")
    int markReminderSent(@Param("id") Long id, @Param("waiting") HumanTaskStatus waiting, @Param("now") LocalDateTime now);
    @Query("select h.id from HumanTaskRequest h where h.status=:waiting and h.escalationAt<=:now and h.escalated=false and h.escalationAssignee is not null")
    List<Long> findEscalationDueIds(@Param("waiting") HumanTaskStatus waiting, @Param("now") LocalDateTime now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HumanTaskRequest h set h.assignee=h.escalationAssignee, h.escalated=true, h.updatedAt=:now where h.id=:id and h.status=:waiting and h.escalated=false")
    int escalate(@Param("id") Long id, @Param("waiting") HumanTaskStatus waiting, @Param("now") LocalDateTime now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HumanTaskRequest h set h.status=:cancelled, h.responsePayload='{}', h.completedBy='system', h.completedAt=:now, h.updatedAt=:now where h.workflowExecution.id=:workflowId and h.status=:waiting")
    int cancelWaitingByWorkflow(@Param("workflowId") Long workflowId, @Param("waiting") HumanTaskStatus waiting,
                                @Param("cancelled") HumanTaskStatus cancelled, @Param("now") LocalDateTime now);
}
