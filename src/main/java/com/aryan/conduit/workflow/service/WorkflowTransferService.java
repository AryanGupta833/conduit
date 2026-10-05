package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.JoinCondition;
import com.aryan.conduit.workflow.dto.WorkflowDefinition;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.aryan.conduit.workflow.service.WorkflowService;
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
public class WorkflowTransferService {
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(password|secret|token|credential|private.?key|access.?key|api.?key|authorization|cookie).*");
    private final WorkflowVersionRepository versions;
    private final TaskNodeRepository tasks;
    private final DependencyRepository dependencies;
    private final WorkflowService workflowService;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public WorkflowDefinition export(Long workflowId) {
        WorkflowVersion version = versions.findTopByWorkflow_IdOrderByVersionNumberDesc(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));
        List<TaskNode> nodes = tasks.findByWorkflowVersion_Id(version.getId());
        Map<Long, String> names = new java.util.HashMap<>();
        List<WorkflowDefinition.TaskDefinition> taskDefinitions = nodes.stream().map(node -> {
            names.put(node.getId(), node.getName());
            return new WorkflowDefinition.TaskDefinition(node.getName(), node.getDisplayName(), node.getPluginType(),
                    scrubConfig(node.getConfigurationJson()), node.getTimeoutSeconds(), node.getMaxRetries(), node.getJoinCondition(), node.getXPosition(), node.getYPosition());
        }).toList();
        List<WorkflowDefinition.DependencyDefinition> edges = dependencies.findByParent_WorkflowVersion_Id(version.getId()).stream()
                .map(edge -> new WorkflowDefinition.DependencyDefinition(names.get(edge.getParent().getId()), names.get(edge.getChild().getId()), edge.getCondition(), edge.getExpression())).toList();
        return new WorkflowDefinition(1, version.getWorkflow().getName(), version.getWorkflow().getCronExpression(), taskDefinitions, edges);
    }

    @Transactional
    public Long importDefinition(WorkflowDefinition definition) {
        if (definition == null || definition.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported workflow definition schema version");
        if (definition.name() == null || definition.name().isBlank() || definition.name().length() > 200) throw new IllegalArgumentException("Workflow name is required and must be at most 200 characters");
        List<WorkflowDefinition.TaskDefinition> taskDefinitions = definition.tasks() == null ? List.of() : definition.tasks();
        List<WorkflowDefinition.DependencyDefinition> edgeDefinitions = definition.dependencies() == null ? List.of() : definition.dependencies();
        if (taskDefinitions.size() > 500 || edgeDefinitions.size() > 2000) throw new IllegalArgumentException("Workflow definition exceeds the import size limits");
        Workflow workflow = workflowService.createWorkflow(definition.name().trim(), definition.cronExpression());
        WorkflowVersion version = versions.findByWorkflow_IdAndLatestTrue(workflow.getId()).orElseThrow();
        Map<String, TaskNode> created = new java.util.HashMap<>();
        for (var item : taskDefinitions) {
            if (item.name() == null || item.name().isBlank() || item.name().length() > 200) throw new IllegalArgumentException("Every task must have a unique name of at most 200 characters");
            if (created.containsKey(item.name())) throw new IllegalArgumentException("Duplicate task name: " + item.name());
            if (item.pluginType() == null || item.pluginType().isBlank()) throw new IllegalArgumentException("Task plugin type is required");
            String config = item.configurationJson() == null || item.configurationJson().isBlank() ? "{}" : item.configurationJson();
            try {
                if (!(mapper.readValue(config, Object.class) instanceof Map<?, ?>)) throw new IllegalArgumentException("Task configuration must be a JSON object: " + item.name());
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
                throw new IllegalArgumentException("Task configuration must be valid JSON: " + item.name(), invalidJson);
            }
            com.aryan.conduit.plugin.security.InlineSecretConfigurationGuard.validateJson(config, mapper);
            TaskNode node = tasks.save(TaskNode.builder().workflowVersion(version).name(item.name().trim()).displayName(item.displayName())
                    .pluginType(item.pluginType().trim()).configurationJson(config).timeoutSeconds(item.timeoutSeconds() == null ? 60 : item.timeoutSeconds())
                    .maxRetries(item.maxRetries() == null ? 0 : item.maxRetries()).joinCondition(item.joinCondition() == null ? JoinCondition.ALL_PARENTS : item.joinCondition())
                    .xPosition(item.xPosition()).yPosition(item.yPosition()).build());
            created.put(item.name(), node);
        }
        for (var edge : edgeDefinitions) {
            TaskNode parent = created.get(edge.parentTask()); TaskNode child = created.get(edge.childTask());
            if (parent == null || child == null) throw new IllegalArgumentException("Dependency references an unknown task");
            if (parent == child) throw new IllegalArgumentException("A task cannot depend on itself");
            dependencies.save(Dependency.builder().parent(parent).child(child).condition(edge.condition()).expression(edge.expression()).build());
        }
        return workflow.getId();
    }

    @Transactional
    public Long cloneWorkflow(Long workflowId) {
        WorkflowDefinition exported = export(workflowId);
        return importDefinition(new WorkflowDefinition(1, exported.name() + " (Copy)", exported.cronExpression(), exported.tasks(), exported.dependencies()));
    }

    private String scrubConfig(String json) {
        try {
            Map<String, Object> config = mapper.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<>() { });
            return mapper.writeValueAsString(scrubMap(config));
        } catch (Exception error) {
            return "{}";
        }
    }

    private Map<String, Object> scrubMap(Map<String, Object> input) {
        Map<String, Object> safe = new LinkedHashMap<>();
        input.forEach((key, value) -> safe.put(key, key.equals("$credential") || isCredentialReference(value) ? value : SENSITIVE.matcher(key).matches() ? "" : scrub(value)));
        return safe;
    }

    private Object scrub(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, child) -> { if (key instanceof String name) nested.put(name, name.equals("$credential") || isCredentialReference(child) ? child : SENSITIVE.matcher(name).matches() ? "" : scrub(child)); });
            return nested;
        }
        if (value instanceof List<?> list) return list.stream().map(this::scrub).toList();
        if (value instanceof String text) return text.replaceAll("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@", "$1[REDACTED]@")
                .replaceAll("(?i)([?&](?:password|passwd|secret|token|api[_-]?key|access[_-]?key|authorization|credential)=)[^&#\\s]+", "$1[REDACTED]");
        return value;
    }

    private boolean isCredentialReference(Object value) {
        return value instanceof Map<?, ?> reference && reference.size() == 1
                && reference.get("$credential") instanceof String name && !name.isBlank();
    }
}
