package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.ExecutionContextEntity;
import com.aryan.conduit.execution.repository.ExecutionContextRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** PostgreSQL-backed, merge-safe source of runtime variables and task results. */
@Service
@RequiredArgsConstructor
public class ExecutionContextService {
    private static final int SCHEMA_VERSION = 1;
    private static final String VARIABLES = "variables";
    private static final String TASKS = "tasks";
    private static final java.util.regex.Pattern SENSITIVE_FIELD = java.util.regex.Pattern.compile("(?i).*(password|secret|token|credential|private.?key|access.?key|api.?key|authorization|cookie).*");

    private final ExecutionContextRepository executionContextRepository;
    private final ObjectMapper objectMapper;

    @Value("${conduit.execution-context.max-bytes:1048576}")
    private int maximumContextBytes = 1_048_576;
    @Value("${conduit.execution-context.max-task-result-bytes:262144}")
    private int maximumTaskResultBytes = 262_144;
    private com.aryan.conduit.observability.ConduitMetrics metrics;

    @Autowired(required = false)
    void setMetrics(com.aryan.conduit.observability.ConduitMetrics metrics) { this.metrics = metrics; }

    public Map<String, Object> getVariables(Long workflowExecutionId) {
        return document(workflowExecutionId).get(VARIABLES) instanceof Map<?, ?> values
                ? stringKeyed(values) : Map.of();
    }

    public Map<String, Object> getTaskResults(Long workflowExecutionId) {
        Object tasks = document(workflowExecutionId).get(TASKS);
        return tasks instanceof Map<?, ?> values ? stringKeyed(values) : Map.of();
    }

    public Map<String, Object> getInitialVariables(Long workflowExecutionId) {
        Object initial = document(workflowExecutionId).get("_initialVariables");
        return initial instanceof Map<?, ?> values ? stringKeyed(values) : Map.of();
    }

    /** Captures a non-secret workflow environment profile once when a run is created. */
    @Transactional
    public void initializeVariables(Long workflowExecutionId, Map<String, Object> values) {
        Map<String, Object> initialValues = values == null ? Map.of() : values;
        initialValues.forEach((key, value) -> {
            if (key == null || key.isBlank() || VARIABLES.equals(key) || TASKS.equals(key) || sensitive(key))
                throw new IllegalArgumentException("Environment profile contains an invalid or secret-like variable name");
        });
        update(workflowExecutionId, root -> {
            @SuppressWarnings("unchecked") Map<String, Object> variables = (Map<String, Object>) root.get(VARIABLES);
            variables.clear();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            initialValues.forEach((key, value) -> {
                Object safe = sanitize(value);
                variables.put(key, safe);
                snapshot.put(key, safe);
            });
            root.put("_initialVariables", snapshot);
        });
    }

    @Transactional
    public void putVariable(Long workflowExecutionId, String key, Object value) {
        if (key == null || key.isBlank() || VARIABLES.equals(key) || TASKS.equals(key) || sensitive(key))
            throw new IllegalArgumentException("Execution variable key is invalid or reserved");
        update(workflowExecutionId, root -> {
            @SuppressWarnings("unchecked") Map<String, Object> variables = (Map<String, Object>) root.get(VARIABLES);
            variables.put(key, sanitize(value));
        });
    }

    /** Called by TaskResultHandler inside its transaction, after the task's durable result is known. */
    @Transactional
    public void commitTaskResult(Long workflowExecutionId, Long taskId, String taskName,
                                 String status, Map<String, Object> resultData) {
        Map<String, Object> safeResult = new LinkedHashMap<>();
        safeResult.put("name", taskName == null || taskName.isBlank() ? "task_" + taskId : taskName);
        safeResult.put("status", status);
        safeResult.put("success", "SUCCESS".equals(status));
        if ("SUCCESS".equals(status) && resultData != null) {
            safeResult.put("output", sanitizeOutput(resultData.get("output")));
            safeResult.put("variables", sanitize(resultData.getOrDefault("variables", Map.of())));
            safeResult.put("metadata", sanitize(resultData.getOrDefault("metadata", Map.of())));
            try {
                if (objectMapper.writeValueAsBytes(safeResult).length > maximumTaskResultBytes)
                    throw new ExecutionContextLimitException("Task result exceeds configured limit of " + maximumTaskResultBytes + " bytes");
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("Task result cannot be serialized as JSON", e);
            }
            Object outputVariables = safeResult.get("variables");
            if (outputVariables instanceof Map<?, ?> values) {
                // SET_VARIABLES and other plugin result variables become workflow variables.
                update(workflowExecutionId, root -> {
                    @SuppressWarnings("unchecked") Map<String, Object> variables = (Map<String, Object>) root.get(VARIABLES);
                    for (Map.Entry<String, Object> entry : stringKeyed(values).entrySet()) {
                        if (!sensitive(entry.getKey())) variables.put(entry.getKey(), sanitize(entry.getValue()));
                    }
                    putTask(root, taskId, safeResult);
                });
                return;
            }
        }
        update(workflowExecutionId, root -> putTask(root, taskId, safeResult));
    }

    private void putTask(Map<String, Object> root, Long taskId, Map<String, Object> result) {
        @SuppressWarnings("unchecked") Map<String, Object> tasks = (Map<String, Object>) root.get(TASKS);
        tasks.put(String.valueOf(taskId), result);
    }

    private void update(Long executionId, java.util.function.Consumer<Map<String, Object>> change) {
        try {
            ExecutionContextEntity entity = executionContextRepository.findByWorkflowExecutionIdForUpdate(executionId)
                    .orElseThrow(() -> new IllegalStateException("Execution context not found for workflow execution " + executionId));
            Map<String, Object> root = normalize(parse(entity.getVariableJson()));
            change.accept(root);
            byte[] json = objectMapper.writeValueAsBytes(root);
            if (json.length > maximumContextBytes)
                throw new ExecutionContextLimitException("Execution context exceeds configured limit of " + maximumContextBytes + " bytes");
            entity.setVariableJson(new String(json, java.nio.charset.StandardCharsets.UTF_8));
            executionContextRepository.saveAndFlush(entity);
            if (metrics != null) metrics.executionContextUpdated();
        } catch (IllegalArgumentException e) {
            if (metrics != null) metrics.executionContextUpdateFailed();
            throw e;
        } catch (Exception e) {
            if (metrics != null) metrics.executionContextUpdateFailed();
            throw new RuntimeException("Failed to update execution context for workflow execution " + executionId, e);
        }
    }

    private Map<String, Object> document(Long executionId) {
        ExecutionContextEntity entity = executionContextRepository.findByWorkflowExecution_Id(executionId)
                .orElseThrow(() -> new IllegalStateException("Execution context not found for workflow execution " + executionId));
        return normalize(parse(entity.getVariableJson()));
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("Stored execution context is invalid", e); }
    }

    private Map<String, Object> normalize(Map<String, Object> stored) {
        if (Integer.valueOf(SCHEMA_VERSION).equals(stored.get("_schemaVersion"))) return stored;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_schemaVersion", SCHEMA_VERSION);
        Map<String, Object> variables = new LinkedHashMap<>();
        Map<String, Object> tasks = new LinkedHashMap<>();
        stored.forEach((key, value) -> {
            if (key.startsWith("task_") && key.length() > 5 && value instanceof Map<?, ?> map) {
                String id = key.substring(5);
                Map<String, Object> task = stringKeyed(map);
                task.putIfAbsent("status", "SUCCESS");
                tasks.put(id, task);
            } else variables.put(key, value);
        });
        root.put(VARIABLES, variables);
        root.put(TASKS, tasks);
        return root;
    }

    private Map<String, Object> stringKeyed(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> { if (key instanceof String text) result.put(text, value); });
        return result;
    }

    private boolean sensitive(String key) { return SENSITIVE_FIELD.matcher(key).matches(); }

    private Object sanitize(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> clean = new LinkedHashMap<>();
            source.forEach((key, child) -> {
                if (key instanceof String name && !sensitive(name)) clean.put(name, sanitize(child));
            });
            return clean;
        }
        if (value instanceof java.util.Collection<?> source) return source.stream().map(this::sanitize).toList();
        return value;
    }

    private Object sanitizeOutput(Object output) {
        if (!(output instanceof String text) || text.length() > maximumTaskResultBytes) return output;
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return text;
        try {
            Object parsed = objectMapper.readValue(trimmed, Object.class);
            if (parsed instanceof Map<?, ?> || parsed instanceof java.util.Collection<?>)
                return objectMapper.writeValueAsString(sanitize(parsed));
        } catch (Exception ignored) { }
        return text;
    }
}
