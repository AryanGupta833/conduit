package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.service.ExecutionCreationService;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Claims one due cron occurrence atomically with its workflow execution. */
@Service
@RequiredArgsConstructor
public class WorkflowScheduleClaimService {
    private final WorkflowRepository workflowRepository;
    private final ExecutionCreationService executionCreationService;

    @Transactional
    public Long createExecutionIfDue(Long workflowId, LocalDateTime now) {
        Workflow workflow = workflowRepository.findByIdForUpdate(workflowId).orElse(null);
        if (workflow == null || !Boolean.TRUE.equals(workflow.getActive())
                || workflow.getCronExpression() == null || workflow.getCronExpression().isBlank()) {
            return null;
        }

        CronExpression cron = CronExpression.parse(workflow.getCronExpression());
        LocalDateTime base = workflow.getLastScheduledRun() == null
                ? workflow.getCreatedAt() : workflow.getLastScheduledRun();
        LocalDateTime dueAt = cron.next(base);
        if (dueAt == null || dueAt.isAfter(now)) return null;

        // Cursor and execution share this transaction. A rollback leaves the
        // occurrence due; competing instances serialize on the workflow row.
        Long executionId = executionCreationService.createExecution(workflowId)
                .workflowExecution().getId();
        workflow.setLastScheduledRun(dueAt);
        workflowRepository.save(workflow);
        return executionId;
    }
}
