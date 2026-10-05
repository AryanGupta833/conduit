package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.JoinCondition;
import com.aryan.conduit.execution.entity.DependencyCondition;

import java.util.List;

public record WorkflowDefinition(int schemaVersion, String name, String cronExpression,
                                 List<TaskDefinition> tasks, List<DependencyDefinition> dependencies) {
    public record TaskDefinition(String name, String displayName, String pluginType, String configurationJson,
                                 Integer timeoutSeconds, Integer maxRetries, JoinCondition joinCondition,
                                 Double xPosition, Double yPosition) { }
    public record DependencyDefinition(String parentTask, String childTask,
                                       DependencyCondition condition, String expression) { }
}
