package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.JoinCondition;

public record GraphNodeResponse(
        Long id,
        String name,
        Double xPosition,
        Double yPosition,
        String pluginType,
        Integer timeoutSeconds,
        Integer maxRetries,
        JoinCondition joinCondition,
        String configurationJson
) {
}
