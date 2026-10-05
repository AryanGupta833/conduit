package com.aryan.conduit.workflow.service;

import com.aryan.conduit.plugin.PluginManager;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.workflow.dto.WorkflowValidationResponse;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.aryan.conduit.workflow.repository.WorkflowCredentialRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only preflight checks; this never invokes plugin execution or external integrations. */
@Service
@RequiredArgsConstructor
public class WorkflowValidationService {
    private final WorkflowVersionRepository versions;
    private final TaskNodeRepository tasks;
    private final WorkflowGraphService graph;
    private final PluginManager plugins;
    private final ObjectMapper mapper;
    private final WorkflowCredentialRepository credentials;

    public WorkflowValidationResponse validate(Long workflowId) {
        WorkflowVersion version = versions.findTopByWorkflow_IdOrderByVersionNumberDesc(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));
        List<WorkflowValidationResponse.ValidationIssue> errors = new ArrayList<>();
        List<WorkflowValidationResponse.ValidationIssue> warnings = new ArrayList<>();
        List<Long> order = List.of();
        List<TaskNode> nodes = tasks.findByWorkflowVersion_Id(version.getId());
        if (nodes.isEmpty()) errors.add(issue(null, null, "Workflow has no tasks."));

        Map<String, PluginMetadata> metadata = plugins.getPluginMetadata().stream()
                .collect(java.util.stream.Collectors.toMap(m -> m.type().toUpperCase(), m -> m));
        for (TaskNode task : nodes) {
            PluginMetadata plugin = metadata.get(task.getPluginType().toUpperCase());
            if (plugin == null) {
                errors.add(issue(task, "Plugin type is not registered: " + task.getPluginType()));
                continue;
            }
            Map<String, Object> config;
            try {
                config = mapper.readValue(task.getConfigurationJson() == null || task.getConfigurationJson().isBlank()
                        ? "{}" : task.getConfigurationJson(), new TypeReference<>() { });
                if (config == null) throw new IllegalArgumentException("Configuration must be a JSON object.");
                com.aryan.conduit.plugin.security.InlineSecretConfigurationGuard.validateJson(task.getConfigurationJson(), mapper);
            } catch (Exception invalidJson) {
                errors.add(issue(task, "Configuration must be a valid JSON object."));
                continue;
            }
            validateCredentialReferences(config, version.getWorkflow().getId(), task, errors, 0);
            Map<String, Object> schema = plugin.configurationSchema();
            if (schema == null || schema.isEmpty()) {
                warnings.add(issue(task, "Plugin does not publish a configuration schema; detailed preflight validation is unavailable."));
                continue;
            }
            Object required = schema.get("required");
            if (required instanceof List<?> values) {
                for (Object name : values) {
                    if (name instanceof String key && (config.get(key) == null || config.get(key) instanceof String value && value.isBlank()))
                        errors.add(issue(task, "Required configuration field is missing: " + key));
                }
            }
            Object propertiesValue = schema.get("properties");
            if (propertiesValue instanceof Map<?, ?> properties) {
                properties.forEach((key, definition) -> {
                    if (!(key instanceof String field) || !config.containsKey(field) || !(definition instanceof Map<?, ?> spec)) return;
                    Object type = spec.get("type");
                    Object value = config.get(field);
                    if (type instanceof String expected && !isCredentialReference(value) && !matchesType(value, expected))
                        errors.add(issue(task, "Configuration field '" + field + "' must be " + expected + "."));
                    Object allowed = spec.get("enum");
                    if (!isCredentialReference(value) && allowed instanceof List<?> options && !options.contains(value))
                        errors.add(issue(task, "Configuration field '" + field + "' must match an allowed value."));
                });
            }
        }
        try {
            order = graph.generateExecutionPlan(version.getId()).executionOrder();
        } catch (RuntimeException invalidGraph) {
            errors.add(issue(null, null, invalidGraph.getMessage() == null ? "Workflow graph is invalid." : invalidGraph.getMessage()));
        }
        return new WorkflowValidationResponse(errors.isEmpty(), version.getId(), order, List.copyOf(errors), List.copyOf(warnings));
    }

    private static boolean matchesType(Object value, String type) {
        return switch (type.toLowerCase()) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Number number && number.doubleValue() == number.intValue();
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            default -> true;
        };
    }

    private static boolean isCredentialReference(Object value) {
        return value instanceof Map<?, ?> reference && reference.size() == 1
                && reference.get("$credential") instanceof String name && !name.isBlank();
    }

    private void validateCredentialReferences(Object value, Long workflowId, TaskNode task,
                                              List<WorkflowValidationResponse.ValidationIssue> errors, int depth) {
        if (depth > 32) return; // The secret guard reports excessive nesting separately.
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("$credential")) {
                Object rawName = map.get("$credential");
                if (map.size() == 1 && rawName instanceof String name && !name.isBlank()
                        && credentials.findByWorkflow_IdAndName(workflowId, name).isEmpty()) {
                    errors.add(issue(task, "Referenced workflow credential is not configured: " + name));
                }
                return;
            }
            map.values().forEach(child -> validateCredentialReferences(child, workflowId, task, errors, depth + 1));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> validateCredentialReferences(child, workflowId, task, errors, depth + 1));
        }
    }

    private static WorkflowValidationResponse.ValidationIssue issue(TaskNode task, String message) {
        return issue(task.getId(), task.getName(), message);
    }

    private static WorkflowValidationResponse.ValidationIssue issue(Long taskId, String taskName, String message) {
        return new WorkflowValidationResponse.ValidationIssue(taskId, taskName, message);
    }
}
