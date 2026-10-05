package com.aryan.conduit.execution.dto;

import com.aryan.conduit.execution.entity.HumanTaskStatus;
import java.time.LocalDateTime;

public record HumanTaskResponse(Long id, Long workflowExecutionId, Long taskExecutionId, HumanTaskStatus status,
                                String type, String title, String description, String assignee,
                                String createdBy, String completedBy, LocalDateTime createdAt,
                                LocalDateTime expiresAt, LocalDateTime completedAt, Object response) { }
