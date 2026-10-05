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
                                        , "MAP", "FILTER", "ARRAY_SELECT"
                                        )
                                ),
                                "path", Map.of(
                                        "type", "string",
                                        "description",
                                        "Dot-separated JSON path for EXTRACT"
                                ),
                                "mapping", Map.of("type", "object", "description", "Output field name to source JSON path"),
                                "field", Map.of("type", "string"), "equals", Map.of(), "index", Map.of("type", "integer"),
                                "maxOutputBytes", Map.of("type", "integer", "default", 1048576, "maximum", 5242880)
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
                "EXTRACT", "MAP", "FILTER", "ARRAY_SELECT"
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
        if ("MAP".equals(normalized) && (!(configuration.get("mapping") instanceof Map<?, ?> m) || m.isEmpty() || m.size() > 100))
            throw new PluginConfigurationException("JSON_TRANSFORM MAP requires 1 to 100 mapping entries");
        if ("FILTER".equals(normalized) && (!(configuration.get("field") instanceof String f) || f.isBlank() || !configuration.containsKey("equals")))
            throw new PluginConfigurationException("JSON_TRANSFORM FILTER requires field and equals");
        if ("ARRAY_SELECT".equals(normalized) && (!(configuration.get("index") instanceof Number n) || n.intValue() < 0 || n.intValue() > 10000))
            throw new PluginConfigurationException("JSON_TRANSFORM ARRAY_SELECT requires a non-negative index");
        Object max = configuration.get("maxOutputBytes");
        if (max != null && (!(max instanceof Number n) || n.intValue() < 1 || n.intValue() > 5 * 1024 * 1024))
            throw new PluginConfigurationException("maxOutputBytes must be between 1 and 5242880");
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
                case "MAP" -> map(input, (Map<?, ?>) configuration.get("mapping"), outputLimit(configuration));
                case "FILTER" -> filter(input, String.valueOf(configuration.get("field")), configuration.get("equals"), outputLimit(configuration));
                case "ARRAY_SELECT" -> arraySelect(input, ((Number) configuration.get("index")).intValue(), outputLimit(configuration));

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

    private int outputLimit(Map<String,Object> c) { return c.get("maxOutputBytes") instanceof Number n ? n.intValue() : 1024 * 1024; }
    private JsonNode node(Object input) throws Exception { return input instanceof String s ? objectMapper.readTree(s) : objectMapper.valueToTree(input); }
    private String bounded(Object value, int limit) throws Exception {
        String json = objectMapper.writeValueAsString(value);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > limit) throw new PluginConfigurationException("JSON_TRANSFORM output exceeds maxOutputBytes");
        return json;
    }
    private JsonNode at(JsonNode root, String path) {
        JsonNode current = root;
        for (String p : path.split("\\.")) { if (p.isBlank() || current == null) return null; current = current.isArray() && p.matches("\\d+") ? current.path(Integer.parseInt(p)) : current.get(p); }
        return current;
    }
    private PluginResult map(Object input, Map<?,?> fields, int limit) throws Exception {
        JsonNode root = node(input); com.fasterxml.jackson.databind.node.ObjectNode out = objectMapper.createObjectNode();
        for (var e : fields.entrySet()) {
            if (!(e.getKey() instanceof String name) || !(e.getValue() instanceof String path) || !name.matches("[A-Za-z_][A-Za-z0-9_-]{0,99}")) throw new PluginConfigurationException("JSON_TRANSFORM mapping names and paths must be strings");
            JsonNode value = at(root, path); if (value != null && !value.isMissingNode()) out.set(name, value);
        }
        return success("MAP", bounded(out, limit));
    }
    private PluginResult filter(Object input, String field, Object expected, int limit) throws Exception {
        JsonNode root = node(input); if (!root.isArray()) throw new PluginConfigurationException("JSON_TRANSFORM FILTER input must be an array");
        var out = objectMapper.createArrayNode(); int count = 0;
        for (JsonNode item : root) { if (item.size() > 10000) throw new PluginConfigurationException("JSON_TRANSFORM item exceeds traversal limit"); JsonNode value = at(item, field);
            if (value != null && !value.isMissingNode() && objectMapper.valueToTree(expected).equals(value)) { if (++count > 10000) throw new PluginConfigurationException("JSON_TRANSFORM matched too many array elements"); out.add(item); } }
        return success("FILTER", bounded(out, limit));
    }
    private PluginResult arraySelect(Object input, int index, int limit) throws Exception {
        JsonNode root = node(input); if (!root.isArray()) throw new PluginConfigurationException("JSON_TRANSFORM ARRAY_SELECT input must be an array");
        JsonNode selected = root.path(index); return selected.isMissingNode() ? success("ARRAY_SELECT", null) : success("ARRAY_SELECT", bounded(selected, limit));
    }
    private PluginResult success(String operation, String output) { return PluginResult.builder().success(true).output(output).metadata(Map.of("operation", operation)).build(); }

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
