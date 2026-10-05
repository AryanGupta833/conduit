package com.aryan.conduit.execution.dto;

import java.time.LocalDateTime;

public record ExecutionEventResponse(Long id, Long taskExecutionId, String type, String status,
                                     LocalDateTime occurredAt, String detail) { }
