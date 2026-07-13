package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.DependencyCondition;

public record GraphEdgeResponse(
        Long id,
        Long source,
        Long target,
        DependencyCondition condition,
        String expression
) {
}
