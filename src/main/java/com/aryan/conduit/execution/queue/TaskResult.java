package com.aryan.conduit.execution.queue;

import java.util.Map;

public record TaskResult(
        Long workflowExecutionId,
        Long taskExecutionId,
        Long taskNodeId,
        boolean success,
        boolean timeout,
        String errorMessage,
        Map<String, Object> resultData
) {
    public TaskResult(Long workflowExecutionId, Long taskExecutionId, Long taskNodeId,
                      boolean success, boolean timeout, String errorMessage) {
        this(workflowExecutionId, taskExecutionId, taskNodeId, success, timeout, errorMessage, Map.of());
    }
}
