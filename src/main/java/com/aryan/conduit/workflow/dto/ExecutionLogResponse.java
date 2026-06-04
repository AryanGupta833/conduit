package com.aryan.conduit.workflow.dto;

import java.time.LocalDateTime;

public record ExecutionLogResponse(
        LocalDateTime timeStamp,
        String level,
        String message
) {
}
