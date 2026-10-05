package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a restart-safe expression view from PostgreSQL durable state. */
@Service
@RequiredArgsConstructor
public class ExpressionContextService {
    private final ExecutionContextService executionContextService;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final ObjectMapper objectMapper;

    public Map<String, Object> buildContext(Long workflowExecutionId) {
        Map<String, Object> variables = new LinkedHashMap<>(executionContextService.getVariables(workflowExecutionId));
        Map<String, Object> legacyTaskAliases = new LinkedHashMap<>();
        Map<String, Object> taskResults = executionContextService.getTaskResults(workflowExecutionId);
        WorkflowExecution execution = workflowExecutionRepository.findById(workflowExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow execution not found: " + workflowExecutionId));
        List<TaskNode> nodes = taskNodeRepository.findByWorkflowVersion_Id(execution.getWorkflowVersion().getId());
        Map<Long, TaskExecution> executions = new LinkedHashMap<>();
        for (TaskExecution task : taskExecutionRepository.findByWorkflowExecution_Id(workflowExecutionId))
            executions.put(task.getTaskNode().getId(), task);

        Map<String, Object> tasks = new LinkedHashMap<>();
        Map<String, Object> tasksById = new LinkedHashMap<>();
        for (TaskNode node : nodes) {
            String name = node.getName();
            if (name == null || name.isBlank()) continue;
            Object persisted = taskResults.get(String.valueOf(node.getId()));
            Map<String, Object> result = persisted instanceof Map<?, ?> map ? stringKeyed(map) : new LinkedHashMap<>();
            normalizeJsonOutput(result);
            TaskExecution taskExecution = executions.get(node.getId());
            if (taskExecution != null) result.put("status", taskExecution.getStatus().name());
            if (result.isEmpty()) continue;
            tasks.put(name, result);
            tasksById.put("task_" + node.getId(), result);
            // Preserve the existing `${taskName.output...}` expression convention.
            legacyTaskAliases.put(name, result);
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("variables", variables);
        context.put("tasks", tasks);
        context.put("tasksById", tasksById);
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("executionId", execution.getId());
        workflow.put("version", execution.getVersionNumber());
        workflow.put("status", execution.getStatus() == null ? "UNKNOWN" : execution.getStatus().name());
        context.put("workflow", workflow);
        // Legacy top-level variable paths continue to resolve.
        variables.forEach(context::putIfAbsent);
        legacyTaskAliases.forEach(context::putIfAbsent);
        return context;
    }

    private Map<String, Object> stringKeyed(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> { if (key instanceof String text) result.put(text, value); });
        return result;
    }

    private void normalizeJsonOutput(Map<String, Object> result) {
        Object output = result.get("output");
        if (!(output instanceof String text) || text.isBlank()) return;
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return;
        try {
            Object parsed = objectMapper.readValue(trimmed, Object.class);
            if (parsed instanceof Map<?, ?> || parsed instanceof List<?>) result.put("output", parsed);
        } catch (Exception ignored) {
            // Non-JSON plugin output remains the original string.
        }
    }
}
