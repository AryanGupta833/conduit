package com.aryan.conduit.execution.dto;

import java.time.LocalDateTime;

public record ExecutionNotificationResponse(Long id, String type, String title, String message,
                                            Long workflowExecutionId, LocalDateTime createdAt,
                                            LocalDateTime readAt) { }
