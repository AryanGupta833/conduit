package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.DependencyCondition;

public record CreateDependencyRequest(
        Long parentTaskId,
        Long childTaskId,
        DependencyCondition condition,
        String expression
) {
}