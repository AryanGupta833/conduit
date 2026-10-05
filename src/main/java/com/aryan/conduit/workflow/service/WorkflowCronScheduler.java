package com.aryan.conduit.workflow.service;

import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowCronScheduler {
    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final WorkflowScheduleClaimService claimService;
    private final WorkflowExecutionAsyncService executionAsyncService;

    @Scheduled(fixedDelayString = "${conduit.scheduler.workflow-poll-ms:10000}")
    public void dispatchDueWorkflows() {
        LocalDateTime now = LocalDateTime.now();
        for (Workflow workflow : workflowRepository.findByActiveTrue()) {
            if (workflow.getCronExpression() == null || workflow.getCronExpression().isBlank()) continue;
            if (!workflowVersionRepository.existsByWorkflow_IdAndPublishedTrue(workflow.getId())) continue;
            try {
                Long executionId = claimService.createExecutionIfDue(workflow.getId(), now);
                if (executionId != null) executionAsyncService.executeAsync(executionId);
            } catch (RuntimeException failure) {
                log.error("Unable to schedule workflow {}", workflow.getId(), failure);
            }
        }
    }
}
