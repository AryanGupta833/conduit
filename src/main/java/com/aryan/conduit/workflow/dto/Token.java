package com.aryan.conduit.workflow.dto;
import com.aryan.conduit.execution.entity.TokenType;

public record Token(
        TokenType type,
        String value
) {
}
