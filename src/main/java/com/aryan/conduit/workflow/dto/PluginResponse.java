package com.aryan.conduit.workflow.dto;

import java.util.List;
import java.util.Map;

public record PluginResponse(
        String type, String description, List<String> inputs, List<String> outputs, Map<String,Object> configExample
        ) {
}
