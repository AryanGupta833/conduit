package com.aryan.conduit.workflow.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record WorkflowEnvironmentProfileResponse(Long id, String name, Map<String, Object> values,
                                                 boolean selectedForRuns, LocalDateTime createdAt,
                                                 LocalDateTime updatedAt) { }
