package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.ExpressionNode;

public record LiteralNode(Object value) implements ExpressionNode {
}
