package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.ExecutionEvent;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.ExecutionEventRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ExecutionEventService {
    private final ExecutionEventRepository events;
    private final WorkflowExecutionRepository executions;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Long executionId, Long taskExecutionId, String type, String status, String detail) {
        WorkflowExecution execution = executions.getReferenceById(executionId);
        String safeDetail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 2000));
        events.save(ExecutionEvent.builder().workflowExecution(execution).taskExecutionId(taskExecutionId)
                .type(type).status(status).occurredAt(LocalDateTime.now()).detail(safeDetail).build());
    }
}
