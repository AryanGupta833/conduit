package com.aryan.conduit.workflow.dto;

public record CreateWorkflowRequest(
        String name,
        String cronExpression
) {
}
