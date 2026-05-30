package com.aryan.conduit.workflow.dto;

public record CreateTaskRequest(
        Long workflowId,
        String name
) {
}
