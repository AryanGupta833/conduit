package com.aryan.conduit.plugin.external;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only plugin provider packaged into a temporary independent ServiceLoader JAR. */
public final class TestCustomPlugin implements WorkflowPlugin {
    private final ConcurrentHashMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public TestCustomPlugin() { }

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata("CUSTOM_EXAMPLE", "Test Custom Plugin",
                "Deterministic test plugin for SDK discovery and workflow execution.", "test-1.0",
                Map.of("type", "object", "required", List.of("message"), "properties", Map.of(
                        "message", Map.of("type", "string"),
                        "recipientVariable", Map.of("type", "string"),
                        "failuresBeforeSuccess", Map.of("type", "integer", "minimum", 0),
                        "scenario", Map.of("type", "string"))));
    }

    @Override
    public void validateConfiguration(Map<String, Object> configuration) {
        Object message = configuration.get("message");
        if (!(message instanceof String value) || value.isBlank()) {
            throw new PluginConfigurationException("CUSTOM_EXAMPLE requires a non-empty 'message'");
        }
        Object failures = configuration.getOrDefault("failuresBeforeSuccess", 0);
        if (!(failures instanceof Number number) || number.intValue() < 0) {
            throw new PluginConfigurationException("'failuresBeforeSuccess' must be a non-negative number");
        }
    }

    @Override
    public PluginResult execute(PluginContext context) {
        Map<String, Object> config = context.configuration();
        int failCount = ((Number) config.getOrDefault("failuresBeforeSuccess", 0)).intValue();
        String scenario = String.valueOf(config.getOrDefault("scenario", "default"));
        if (failCount > 0) {
            int attempt = attempts.computeIfAbsent(scenario, ignored -> new AtomicInteger()).incrementAndGet();
            if (attempt <= failCount) {
                return PluginResult.builder().success(false)
                        .output("Custom test failure " + attempt + " of " + failCount)
                        .metadata(Map.of("attempt", attempt, "scenario", scenario)).build();
            }
        }
        String message = String.valueOf(config.get("message"));
        String variableName = String.valueOf(config.getOrDefault("recipientVariable", "username"));
        Object user = context.variables().get(variableName);
        String output = user == null ? message : message + " for " + user;
        return PluginResult.builder().success(true).output(output)
                .variables(Map.of("customMessage", output))
                .metadata(Map.of("plugin", "CUSTOM_EXAMPLE", "result", "simulated"))
                .build();
    }
}
