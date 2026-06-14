package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.ExpressionNode;

public record BinaryNode(
        ExpressionNode left,String operator,ExpressionNode right
) implements  ExpressionNode{
}
