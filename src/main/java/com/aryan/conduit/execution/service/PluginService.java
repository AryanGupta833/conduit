package com.aryan.conduit.execution.service;

import com.aryan.conduit.plugin.PluginManager;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.workflow.dto.PluginResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PluginService {
    private final PluginManager pluginManager;

    public PluginService(PluginManager pluginManager) { this.pluginManager = pluginManager; }

    public List<PluginResponse> getPlugins() {
        return pluginManager.getPluginMetadata().stream().map(this::toResponse).toList();
    }

    private PluginResponse toResponse(PluginMetadata metadata) {
        List<String> required = metadata.configurationSchema().get("required") instanceof List<?> values
                ? values.stream().map(String::valueOf).toList() : List.of();
        Map<String, Object> example = "HUMAN_TASK".equals(metadata.type())
                ? Map.of("type", "APPROVAL", "title", "Review required", "assignee", "team-name",
                        "timeoutSeconds", 86400, "timeoutPolicy", "FAIL")
                : Map.of();
        return new PluginResponse(metadata.type(), metadata.description(), required,
                List.of("output", "variables", "metadata"), example, metadata.displayName(),
                metadata.version(), metadata.configurationSchema());
    }
}
