package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.workflow.entity.WorkflowStatus;

import java.time.LocalDateTime;

public record WorkflowResponse(
        Long id,
        String name,
        WorkflowStatus status,
        Boolean active,
        String cronExpression,
        Integer latestVersion,
        LocalDateTime createdAt,
        LocalDateTime lastScheduledRun,
        LocalDateTime nextScheduledRun
) {
}
