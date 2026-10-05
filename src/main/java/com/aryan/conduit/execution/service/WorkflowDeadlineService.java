package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class WorkflowDeadlineService {
    private final WorkflowExecutionRepository executions;
    private final ExecutionControlService control;

    @Scheduled(fixedDelayString = "${conduit.execution.deadline-scan-ms:5000}")
    public void expireOverdueExecutions() {
        List<WorkflowExecutionStatus> active = List.of(WorkflowExecutionStatus.RUNNING,
                WorkflowExecutionStatus.PAUSED, WorkflowExecutionStatus.WAITING_FOR_HUMAN);
        executions.findByStatusInAndDeadlineAtBefore(active, LocalDateTime.now())
                .forEach(execution -> control.timeout(execution.getId()));
    }
}
