package com.aryan.conduit.execution.dto;

import java.util.List;

public record ExecutionComparisonResponse(Long firstExecutionId, Long secondExecutionId,
                                          List<TaskComparison> tasks) {
    public record TaskComparison(String taskName, String firstStatus, String secondStatus,
                                 Integer firstRetryCount, Integer secondRetryCount,
                                 boolean resultChanged) { }
}
