package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.LogLevel;

import java.time.LocalDateTime;

public record ExecutionLogResponse(
        Long id,
        LogLevel level,
        String message,
        LocalDateTime timestamp
) {
}
