package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.TaskExecutionStatus;

public record ExecutionGraphNode(
        Long id, String label, Double x, Double y, String plugin, TaskExecutionStatus status
        ) {
}
