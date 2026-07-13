package com.aryan.conduit.workflow.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record WorkflowVersionDiffResponse(
        List<String> addedTasks,List<String> removedTasks,List<TaskDifference> modifiedTasks,List<String> addedDependencies,List<String> removedDependencies
) {
}
