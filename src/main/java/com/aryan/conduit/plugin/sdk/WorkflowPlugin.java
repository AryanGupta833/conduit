package com.aryan.conduit.plugin.sdk;

import java.util.Map;

/** Minimal extension contract. Implementations are discovered through Java ServiceLoader. */
public interface WorkflowPlugin {
    PluginMetadata metadata();

    PluginResult execute(PluginContext context) throws Exception;

    /** Plugin-owned configuration validation; throw a readable exception for invalid input. */
    default void validateConfiguration(Map<String, Object> configuration) throws PluginConfigurationException { }
}
