package com.example.conduit;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic sample extension. Failure sequencing exists only to demonstrate Conduit's retries. */
public final class ExampleNotificationPlugin implements WorkflowPlugin {
    private final ConcurrentHashMap<String, AtomicInteger> scenarioAttempts = new ConcurrentHashMap<>();

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata("CUSTOM_EXAMPLE", "Example Notification",
                "Returns a deterministic notification message and demonstrates variables and retries.", "1.0.0",
                Map.of("type", "object", "required", List.of("message"), "properties", Map.of(
                        "message", Map.of("type", "string"),
                        "recipientVariable", Map.of("type", "string"),
                        "scenario", Map.of("type", "string"),
                        "failuresBeforeSuccess", Map.of("type", "integer", "minimum", 0))));
    }

    @Override
    public void validateConfiguration(Map<String, Object> configuration) {
        Object message = configuration.get("message");
        if (!(message instanceof String text) || text.isBlank()) {
            throw new PluginConfigurationException("CUSTOM_EXAMPLE requires a non-empty 'message'");
        }
        Object failures = configuration.getOrDefault("failuresBeforeSuccess", 0);
        if (!(failures instanceof Number number) || number.intValue() < 0) {
            throw new PluginConfigurationException("'failuresBeforeSuccess' must be a non-negative integer");
        }
        Object recipientVariable = configuration.get("recipientVariable");
        if (recipientVariable != null && !(recipientVariable instanceof String)) {
            throw new PluginConfigurationException("'recipientVariable' must be a string");
        }
    }

    @Override
    public PluginResult execute(PluginContext context) {
        Map<String, Object> config = context.configuration();
        int failuresBeforeSuccess = ((Number) config.getOrDefault("failuresBeforeSuccess", 0)).intValue();
        String scenario = String.valueOf(config.getOrDefault("scenario", "default"));
        if (failuresBeforeSuccess > 0) {
            int attempt = scenarioAttempts.computeIfAbsent(scenario, ignored -> new AtomicInteger()).incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                return PluginResult.builder().success(false)
                        .output("CUSTOM_EXAMPLE configured failure " + attempt + " of " + failuresBeforeSuccess)
                        .metadata(Map.of("scenario", scenario, "attempt", attempt)).build();
            }
        }

        String message = (String) config.get("message");
        String recipientKey = String.valueOf(config.getOrDefault("recipientVariable", "username"));
        Object recipient = context.variables().get(recipientKey);
        String output = recipient == null ? message : message + " for " + recipient;
        return PluginResult.builder().success(true).output(output)
                .variables(Map.of("customMessage", output))
                .metadata(Map.of("delivery", "simulated", "pluginVersion", "1.0.0"))
                .build();
    }
}
