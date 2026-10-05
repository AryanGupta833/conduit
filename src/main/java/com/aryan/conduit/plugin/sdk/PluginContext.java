package com.aryan.conduit.plugin.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable inputs presented to a plugin. Contains no Conduit persistence or worker types. */
public record PluginContext(
        String taskName,
        Integer timeoutSeconds,
        Map<String, Object> configuration,
        Map<String, Object> variables,
        PluginWorkspace workspace,
        String idempotencyKey) {

    public PluginContext(String taskName, Integer timeoutSeconds, Map<String, Object> configuration,
                         Map<String, Object> variables, PluginWorkspace workspace) {
        this(taskName, timeoutSeconds, configuration, variables, workspace, null);
    }

    /** Retained for plugins compiled against the original SDK constructor. */
    public PluginContext(String taskName, Integer timeoutSeconds, Map<String, Object> configuration,
                         Map<String, Object> variables) {
        this(taskName, timeoutSeconds, configuration, variables, null, null);
    }

    public PluginContext {
        configuration = immutableCopy(configuration);
        variables = immutableCopy(variables);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        return values == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
