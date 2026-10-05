package com.aryan.conduit.trigger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface WorkflowTriggerInboxRepository extends JpaRepository<WorkflowTriggerInbox, UUID> {
    @Modifying
    @Query(value = """
            INSERT INTO workflow_trigger_inbox (event_id, workflow_id, created_at)
            VALUES (:eventId, :workflowId, CURRENT_TIMESTAMP)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int reserve(@Param("eventId") UUID eventId, @Param("workflowId") Long workflowId);

    @Modifying
    @Query(value = "UPDATE workflow_trigger_inbox SET execution_id = :executionId WHERE event_id = :eventId AND execution_id IS NULL", nativeQuery = true)
    int attachExecution(@Param("eventId") UUID eventId, @Param("executionId") Long executionId);
}
