package com.aryan.conduit.execution.dto;

import com.aryan.conduit.execution.entity.TaskExecutionStatus;

import java.util.Map;

public record TaskExecutionDetailsResponse(Long id, String taskName, String pluginType,
                                           TaskExecutionStatus status, Integer retryCount,
                                           Map<String, Object> resolvedInputs,
                                           Object output, Object variables, Object metadata) { }
