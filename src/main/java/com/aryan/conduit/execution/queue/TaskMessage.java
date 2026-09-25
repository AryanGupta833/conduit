package com.aryan.conduit.execution.queue;

public record TaskMessage(
        Long workflowExecutionId,
        Long taskExecutionId,
        Long taskNodeId
) {
}