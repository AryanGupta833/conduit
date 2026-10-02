package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JsonTransformPlugin implements WorkflowPlugin {

    private final ObjectMapper objectMapper;

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata(
                "JSON_TRANSFORM",
                "JSON Transform",
                "Parse, stringify, or extract values from JSON data.",
                "1.0.0",
                Map.of(
                        "type", "object",
                        "required", List.of(
                                "input",
                                "operation"
                        ),
                        "properties", Map.of(
                                "input", Map.of(
                                        "description",
                                        "JSON input or object to transform"
                                ),
                                "operation", Map.of(
                                        "type", "string",
                                        "enum", List.of(
                                                "PARSE",
                                                "STRINGIFY",
                                                "EXTRACT"
                                        )
                                ),
                                "path", Map.of(
                                        "type", "string",
                                        "description",
                                        "Dot-separated JSON path for EXTRACT"
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
                    "JSON_TRANSFORM configuration is required"
            );
        }

        if (!configuration.containsKey("input")) {
            throw new PluginConfigurationException(
                    "JSON_TRANSFORM requires 'input'"
            );
        }

        Object operationValue =
                configuration.get("operation");

        if (!(operationValue instanceof String operation)
                || operation.isBlank()) {

            throw new PluginConfigurationException(
                    "JSON_TRANSFORM requires 'operation'"
            );
        }

        String normalized =
                operation.toUpperCase();

        if (!List.of(
                "PARSE",
                "STRINGIFY",
                "EXTRACT"
        ).contains(normalized)) {

            throw new PluginConfigurationException(
                    "Unsupported JSON_TRANSFORM operation: "
                            + operation
            );
        }

        if ("EXTRACT".equals(normalized)) {

            Object path =
                    configuration.get("path");

            if (!(path instanceof String pathString)
                    || pathString.isBlank()) {

                throw new PluginConfigurationException(
                        "JSON_TRANSFORM EXTRACT requires 'path'"
                );
            }
        }
    }

    @Override
    public PluginResult execute(PluginContext context) {

        try {
            validateConfiguration(context.configuration());

            Map<String, Object> configuration =
                    context.configuration();

            Object input =
                    configuration.get("input");

            String operation =
                    String.valueOf(
                            configuration.get("operation")
                    ).toUpperCase();

            return switch (operation) {

                case "PARSE" ->
                        parse(input);

                case "STRINGIFY" ->
                        stringify(input);

                case "EXTRACT" ->
                        extract(
                                input,
                                String.valueOf(
                                        configuration.get("path")
                                )
                        );

                default ->
                        throw new PluginConfigurationException(
                                "Unsupported operation: "
                                        + operation
                        );
            };

        } catch (PluginConfigurationException ex) {

            throw ex;

        } catch (Exception ex) {

            return PluginResult.builder()
                    .success(false)
                    .output(
                            ex.getMessage() == null
                                    ? ex.getClass().getSimpleName()
                                    : ex.getMessage()
                    )
                    .build();
        }
    }

    private PluginResult parse(Object input)
            throws Exception {

        if (!(input instanceof String json)
                || json.isBlank()) {

            throw new PluginConfigurationException(
                    "JSON_TRANSFORM PARSE requires a non-empty JSON string"
            );
        }

        JsonNode parsed =
                objectMapper.readTree(json);

        return PluginResult.builder()
                .success(true)
                .output(
                        objectMapper.writeValueAsString(parsed)
                )
                .metadata(Map.of(
                        "operation",
                        "PARSE"
                ))
                .build();
    }

    private PluginResult stringify(Object input)
            throws Exception {

        String output =
                objectMapper.writeValueAsString(input);

        return PluginResult.builder()
                .success(true)
                .output(output)
                .metadata(Map.of(
                        "operation",
                        "STRINGIFY"
                ))
                .build();
    }

    private PluginResult extract(
            Object input,
            String path) throws Exception {

        JsonNode root;

        if (input instanceof String json) {
            root = objectMapper.readTree(json);
        } else {
            root = objectMapper.valueToTree(input);
        }

        JsonNode current = root;

        for (String segment : path.split("\\.")) {

            if (segment.isBlank()) {
                throw new PluginConfigurationException(
                        "JSON_TRANSFORM path contains an empty segment"
                );
            }

            if (current == null
                    || current.isMissingNode()
                    || current.isNull()) {

                return PluginResult.builder()
                        .success(true)
                        .output(null)
                        .metadata(Map.of(
                                "operation",
                                "EXTRACT",
                                "path",
                                path,
                                "found",
                                false
                        ))
                        .build();
            }

            current = current.get(segment);
        }

        boolean found =
                current != null
                        && !current.isMissingNode();

        String output =
                found
                        ? objectMapper.writeValueAsString(current)
                        : null;

        return PluginResult.builder()
                .success(true)
                .output(output)
                .metadata(Map.of(
                        "operation",
                        "EXTRACT",
                        "path",
                        path,
                        "found",
                        found
                ))
                .build();
    }
}