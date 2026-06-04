package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;

import java.time.LocalDateTime;

public record WorkflowExecutionDetailResponse(
        Long id, String workflowName, WorkflowExecutionStatus status, LocalDateTime startedAt,LocalDateTime finishedAt
        ) {
}
