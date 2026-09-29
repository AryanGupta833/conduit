package com.aryan.conduit.plugin.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public, developer-facing information describing a plugin and its configuration shape. */
public record PluginMetadata(
        String type,
        String displayName,
        String description,
        String version,
        Map<String, Object> configurationSchema) {

    public PluginMetadata {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Plugin type is required");
        if (displayName == null || displayName.isBlank()) displayName = type;
        if (description == null) description = "";
        if (version == null || version.isBlank()) version = "1.0.0";
        configurationSchema = configurationSchema == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(configurationSchema));
    }

    public static PluginMetadata of(String type, String displayName, String description, String version) {
        return new PluginMetadata(type, displayName, description, version, Map.of());
    }
}
