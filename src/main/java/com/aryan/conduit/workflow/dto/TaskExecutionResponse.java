package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.TaskExecutionStatus;

public record TaskExecutionResponse(
        Long id, String taskName, TaskExecutionStatus status,Integer retryCount
        ) {
}
