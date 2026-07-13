package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.JoinCondition;

public record UpdateTaskRequest(

        String name,

        Integer timeoutSeconds,

        Integer maxRetries,

        String pluginType,

        String configurationJson,

        JoinCondition joinCondition,
        Double xPosition,
        Double yPosition

) {
}
