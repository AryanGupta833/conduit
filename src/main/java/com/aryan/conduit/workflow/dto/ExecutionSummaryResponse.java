package com.aryan.conduit.workflow.dto;


import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record ExecutionSummaryResponse(
    Long workflowExecutionId,
    String workflowName,
    String status,
    Integer totalTasks,
    Integer successfulTasks,
    Integer failedTasks,
    Integer skippedTasks,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    Long durationMs
){
}
