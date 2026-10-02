package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SetVariablesPlugin implements WorkflowPlugin {

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata(
                "SET_VARIABLES",
                "Set Variables",
                "Create or update workflow variables for downstream tasks.",
                "1.0.0",
                Map.of(
                        "type", "object",
                        "required", List.of("variables"),
                        "properties", Map.of(
                                "variables", Map.of(
                                        "type", "object",
                                        "description", "Variables to create or update"
                                )
                        )
                )
        );
    }

    @Override
    public void validateConfiguration(Map<String, Object> configuration)
            throws PluginConfigurationException {

        if (configuration == null) {
            throw new PluginConfigurationException(
                    "SET_VARIABLES configuration is required"
            );
        }

        Object variables = configuration.get("variables");

        if (!(variables instanceof Map<?, ?>)) {
            throw new PluginConfigurationException(
                    "SET_VARIABLES requires 'variables' to be an object"
            );
        }

        for (Object key : ((Map<?, ?>) variables).keySet()) {
            if (key == null || key.toString().isBlank()) {
                throw new PluginConfigurationException(
                        "SET_VARIABLES variable names cannot be blank"
                );
            }
        }
    }

    @Override
    public PluginResult execute(PluginContext context) {

        validateConfiguration(context.configuration());

        Map<String, Object> updatedVariables =
                new LinkedHashMap<>(context.variables());

        Map<?, ?> configuredVariables =
                (Map<?, ?>) context.configuration().get("variables");

        for (Map.Entry<?, ?> entry : configuredVariables.entrySet()) {
            updatedVariables.put(
                    entry.getKey().toString(),
                    entry.getValue()
            );
        }

        return PluginResult.builder()
                .success(true)
                .output(
                        "Updated "
                                + configuredVariables.size()
                                + " workflow variable(s)"
                )
                .variables(updatedVariables)
                .metadata(Map.of(
                        "updatedVariableCount",
                        configuredVariables.size()
                ))
                .build();
    }
}