package com.aryan.conduit.workflow.dto;

import java.util.List;

public record ExecutionGraphResponse(
        List<ExecutionGraphNode> nodes,
        List<ExecutionGraphEdge> edges

) {
}
