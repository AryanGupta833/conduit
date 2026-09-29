package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowRecoveryService {

    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowExecutionAsyncService workflowExecutionAsyncService;

    @EventListener(ApplicationReadyEvent.class)
    public void resumeRunningExecutions() {
        for (WorkflowExecution execution : workflowExecutionRepository.findByStatus(WorkflowExecutionStatus.RUNNING)) {
            log.info("Resuming dispatch for workflow execution {}", execution.getId());
            workflowExecutionAsyncService.executeAsync(execution.getId());
        }
    }
}
