package com.aryan.conduit.workflow.dto;

public record CreateDependencyRequest(
        Long parentTaskId,
        Long childTaskId
) {
}
