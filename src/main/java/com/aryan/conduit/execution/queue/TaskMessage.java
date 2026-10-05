package com.aryan.conduit.execution.queue;

public record TaskMessage(
        Long workflowExecutionId,
        Long taskExecutionId,
        Long taskNodeId,
        String correlationId,
        Integer priority
) {
    public TaskMessage(Long workflowExecutionId, Long taskExecutionId, Long taskNodeId) {
        this(workflowExecutionId, taskExecutionId, taskNodeId, null, 0);
    }

    public TaskMessage(Long workflowExecutionId, Long taskExecutionId, Long taskNodeId, String correlationId) {
        this(workflowExecutionId, taskExecutionId, taskNodeId, correlationId, 0);
    }
}
