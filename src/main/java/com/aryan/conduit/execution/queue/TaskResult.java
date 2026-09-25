package com.aryan.conduit.execution.queue;

public record TaskResult(
        Long workflowExecutionId,
        Long taskExecutionId,
        Long taskNodeId,
        boolean success,
        boolean timeout,
        String errorMessage
) {
}