package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.*;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Marker plugin; task creation and suspension are performed transactionally by TaskRunnerService. */
@Component
public class HumanTaskPlugin implements WorkflowPlugin {
    @Override
    public PluginMetadata metadata() {
        Map<String, Object> properties = Map.of(
                "type", Map.of("type", "string", "enum", java.util.List.of("APPROVAL", "INPUT", "REVIEW", "CONFIRMATION")),
                "title", Map.of("type", "string", "maxLength", 300),
                "description", Map.of("type", "string", "maxLength", 10000),
                "assignee", Map.of("type", "string", "maxLength", 200),
                "metadata", Map.of("type", "object"),
                "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 31536000),
                "timeoutPolicy", Map.of("type", "string", "enum", java.util.List.of("FAIL", "REJECT", "SKIP")));
        properties = new java.util.LinkedHashMap<>(properties);
        properties.put("reminderAfterSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 31536000));
        properties.put("escalationAfterSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 31536000));
        properties.put("escalationAssignee", Map.of("type", "string", "maxLength", 200));
        return new PluginMetadata("HUMAN_TASK", "Human Task", "Pause execution until a human responds", "1.0.0",
                Map.of("type", "object", "required", java.util.List.of("type", "title"), "properties", properties));
    }
    @Override public boolean suspendsExecution() { return true; }
    @Override public PluginResult execute(PluginContext context) { return PluginResult.builder().success(true).output("Waiting for human intervention").build(); }
    @Override public void validateConfiguration(Map<String, Object> config) {
        Object type = config.getOrDefault("type", "APPROVAL");
        if (!(type instanceof String value) || !java.util.Set.of("APPROVAL", "INPUT", "REVIEW", "CONFIRMATION").contains(value.toUpperCase(java.util.Locale.ROOT)))
            throw new PluginConfigurationException("type must be APPROVAL, INPUT, REVIEW, or CONFIRMATION");
        Object title = config.get("title");
        if (title != null && (!(title instanceof String text) || text.isBlank() || text.length() > 300))
            throw new PluginConfigurationException("title must be a non-empty string of at most 300 characters");
        Object timeout = config.get("timeoutSeconds");
        if (timeout != null && (!(timeout instanceof Number number) || number.longValue() < 1 || number.longValue() > 31_536_000))
            throw new PluginConfigurationException("timeoutSeconds must be between 1 and 31536000");
        Object policy = config.get("timeoutPolicy");
        if (policy != null && (!(policy instanceof String text) || !java.util.Set.of("FAIL", "REJECT", "SKIP").contains(text.toUpperCase(java.util.Locale.ROOT))))
            throw new PluginConfigurationException("timeoutPolicy must be FAIL, REJECT, or SKIP");
        Object description = config.get("description");
        if (description != null && (!(description instanceof String text) || text.length() > 10_000))
            throw new PluginConfigurationException("description must be a string of at most 10000 characters");
        Object assignee = config.get("assignee");
        if (assignee != null && (!(assignee instanceof String text) || text.isBlank() || text.length() > 200))
            throw new PluginConfigurationException("assignee must be a non-empty string of at most 200 characters");
        for (String timer : java.util.List.of("reminderAfterSeconds", "escalationAfterSeconds")) {
            Object timerValue = config.get(timer);
            if (timerValue != null && (!(timerValue instanceof Number n) || n.longValue() < 1 || n.longValue() > 31_536_000)) throw new PluginConfigurationException(timer + " must be between 1 and 31536000");
        }
        Object escalatee = config.get("escalationAssignee");
        if (escalatee != null && (!(escalatee instanceof String s) || s.isBlank() || s.length() > 200)) throw new PluginConfigurationException("escalationAssignee must be a non-empty string of at most 200 characters");
        if (config.get("escalationAfterSeconds") != null && escalatee == null) throw new PluginConfigurationException("escalationAssignee is required when escalationAfterSeconds is configured");
        Object metadata = config.get("metadata");
        if (metadata != null && !(metadata instanceof Map<?, ?>))
            throw new PluginConfigurationException("metadata must be a JSON object");
        if (metadata instanceof Map<?, ?> && metadata.toString().length() > 32_768)
            throw new PluginConfigurationException("metadata exceeds 32 KiB");
    }
}
