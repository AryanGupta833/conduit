package com.aryan.conduit.workflow.dto;

import java.util.List;

public record ExecutionPlan(
        Long workflowVersionId,
        List<Long> executionOrder
) {
}
