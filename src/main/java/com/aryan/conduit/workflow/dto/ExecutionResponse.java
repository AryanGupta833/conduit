package com.aryan.conduit.workflow.dto;

import ch.qos.logback.classic.spi.Configurator;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;

import java.time.LocalDateTime;

public record ExecutionResponse(
        Long id,
        Long workflowId,
        Long workflowVersionId,
        String workflowName,
        Integer versionNumber,
        WorkflowExecutionStatus status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs
) {}
