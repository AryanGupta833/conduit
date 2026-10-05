package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.dto.TaskExecutionDetailsResponse;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TaskExecutionDetailsService {
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(password|secret|token|credential|private.?key|access.?key|api.?key|authorization|cookie).*");
    private final TaskExecutionRepository tasks;
    private final TaskOutputService outputs;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public TaskExecutionDetailsResponse get(Long executionId, Long taskExecutionId) {
        var task = tasks.findById(taskExecutionId).orElseThrow(() -> new IllegalArgumentException("Task execution not found"));
        if (!task.getWorkflowExecution().getId().equals(executionId)) throw new IllegalArgumentException("Task does not belong to execution");
        Map<String, Object> inputs = Map.of();
        String json = task.getResolvedConfigurationJson();
        if (json != null && !json.isBlank()) {
            try { inputs = redact(mapper.readValue(json, new TypeReference<Map<String, Object>>() { })); }
            catch (Exception ignored) { inputs = Map.of("_unavailable", "Resolved task inputs could not be parsed"); }
        }
        Map<String, Object> result = outputs.getOutput(executionId, task.getTaskNode().getId());
        return new TaskExecutionDetailsResponse(task.getId(), task.getTaskNode().getName(), task.getTaskNode().getPluginType(),
                task.getStatus(), task.getRetryCount(), inputs,
                result == null ? null : result.get("output"),
                result == null ? null : result.get("variables"),
                result == null ? null : result.get("metadata"));
    }

    private Map<String, Object> redact(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> safe.put(key, SENSITIVE.matcher(key).matches() ? "[REDACTED]" : redactValue(value)));
        return safe;
    }

    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, child) -> { if (key instanceof String name) nested.put(name, SENSITIVE.matcher(name).matches() ? "[REDACTED]" : redactValue(child)); });
            return nested;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redactValue).toList();
        if (value instanceof String text) return text
                .replaceAll("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@", "$1[REDACTED]@")
                .replaceAll("(?i)([?&](?:password|passwd|secret|token|api[_-]?key|access[_-]?key)=)[^&#\\s]+", "$1[REDACTED]")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]");
        return value;
    }
}
