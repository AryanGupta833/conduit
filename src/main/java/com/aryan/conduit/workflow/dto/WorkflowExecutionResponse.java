package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;

import java.time.LocalDateTime;

public record WorkflowExecutionResponse(
        Long id,
        Long workflowId,
        Long workflowVersionId,
        Integer versionNumber,
        WorkflowExecutionStatus status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
