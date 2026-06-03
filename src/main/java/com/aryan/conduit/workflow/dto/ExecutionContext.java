package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.WorkflowExecution;

import java.util.List;
import java.util.Map;

public record ExecutionContext(
        WorkflowExecution workflowExecution,
        List<List<Long>> stages,
        Map<Long, TaskExecution> taskExecutionMap
) {
}