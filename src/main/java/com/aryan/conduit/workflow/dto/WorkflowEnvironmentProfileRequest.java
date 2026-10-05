package com.aryan.conduit.workflow.dto;

import java.util.Map;

public record WorkflowEnvironmentProfileRequest(String name, Map<String, Object> values) { }
