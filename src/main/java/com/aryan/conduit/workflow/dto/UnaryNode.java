package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.ExpressionNode;

public record UnaryNode(String operator, ExpressionNode operand) implements ExpressionNode { }
