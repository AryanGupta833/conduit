package com.aryan.conduit.workflow.dto;

import java.time.LocalDateTime;

public record WorkflowCredentialResponse(Long id, String name, boolean configured, LocalDateTime createdAt, LocalDateTime updatedAt) { }
