package com.aryan.conduit.trigger;

import com.aryan.conduit.execution.service.ExecutionCreationService;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowTriggerInboxService {
    private final WorkflowTriggerInboxRepository inboxRepository;
    private final ExecutionCreationService executionCreationService;

    /**
     * Reserves the producer event and creates its execution in the same
     * PostgreSQL transaction. The primary key arbitrates simultaneous duplicate
     * deliveries; a losing insert observes the committed execution ID.
     */
    @Transactional
    public Long getOrCreateExecution(UUID eventId, Long workflowId) {
        if (eventId == null) {
            // Old Kafka messages did not carry an event ID and cannot be deduplicated.
            return executionCreationService.createExecution(workflowId)
                    .workflowExecution().getId();
        }

        int reserved = inboxRepository.reserve(eventId, workflowId);
        if (reserved == 0) {
            WorkflowTriggerInbox existing = inboxRepository.findById(eventId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Trigger inbox event disappeared: " + eventId));
            if (!workflowId.equals(existing.getWorkflowId())) {
                throw new IllegalArgumentException(
                        "Trigger event ID was reused for a different workflow");
            }
            if (existing.getExecutionId() == null) {
                throw new IllegalStateException(
                        "Trigger inbox event has no associated execution: " + eventId);
            }
            return existing.getExecutionId();
        }

        ExecutionContext context = executionCreationService.createExecution(workflowId);
        Long executionId = context.workflowExecution().getId();
        if (inboxRepository.attachExecution(eventId, executionId) != 1) {
            throw new IllegalStateException(
                    "Unable to associate trigger event with execution: " + eventId);
        }
        return executionId;
    }
}
