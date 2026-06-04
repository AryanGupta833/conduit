package com.aryan.conduit.workflow.dto;

import java.util.List;

public record WorkflowGraphResponse(
        List<GraphNodeResponse> nodes,
        List<GraphEdgeResponse> edges
) {
}
