package com.aryan.conduit.workflow.dto;

import java.util.List;

public record WorkflowValidationResponse(
        boolean valid,
        Long workflowVersionId,
        List<Long> executionOrder,
        List<ValidationIssue> errors,
        List<ValidationIssue> warnings
) {
    public record ValidationIssue(Long taskId, String taskName, String message) { }
}
