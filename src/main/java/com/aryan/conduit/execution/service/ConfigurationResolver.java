package com.aryan.conduit.execution.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Typed JSON interpolation using safe, bounded dotted-path data lookups only. */
@Service
public class ConfigurationResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]{1,512})}");
    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*");
    private static final Pattern SECRET_SEGMENT = Pattern.compile("(?i).*(password|secret|token|credential|private.?key|access.?key|api.?key|authorization|cookie).*");
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final int MAX_DEPTH = 32;
    private final ExpressionContextService expressionContextService;
    private final ObjectMapper objectMapper;
    private com.aryan.conduit.observability.ConduitMetrics metrics;

    @Autowired(required = false)
    void setMetrics(com.aryan.conduit.observability.ConduitMetrics metrics) { this.metrics = metrics; }

    @Autowired
    public ConfigurationResolver(ExpressionContextService expressionContextService, ObjectMapper objectMapper) {
        this.expressionContextService = expressionContextService;
        this.objectMapper = objectMapper;
    }

    /** Compatibility constructor used by focused unit tests and integrations. */
    public ConfigurationResolver(ExpressionContextService expressionContextService) {
        this(expressionContextService, new ObjectMapper());
    }

    public String resolve(Long workflowExecutionId, String configurationJson) {
        try {
            String resolved = resolveConfiguration(workflowExecutionId, configurationJson);
            if (metrics != null) metrics.configurationInterpolation(true);
            return resolved;
        } catch (RuntimeException e) {
            if (metrics != null) metrics.configurationInterpolation(false);
            throw e;
        }
    }

    private String resolveConfiguration(Long workflowExecutionId, String configurationJson) {
        if (configurationJson == null || configurationJson.isBlank()) return configurationJson;
        if (configurationJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
            throw new IllegalArgumentException("Plugin configuration exceeds the 1 MiB interpolation limit");
        try {
            Object config = objectMapper.readValue(configurationJson, new TypeReference<>() {});
            if (!(config instanceof Map<?, ?>)) throw new IllegalArgumentException("Plugin configuration must be a JSON object");
            com.aryan.conduit.plugin.security.InlineSecretConfigurationGuard.validateJson(configurationJson, objectMapper);
            Map<String, Object> context = expressionContextService.buildContext(workflowExecutionId);
            Object resolved = interpolate(config, context, 0);
            byte[] json = objectMapper.writeValueAsBytes(resolved);
            if (json.length > MAX_JSON_BYTES) throw new IllegalArgumentException("Interpolated plugin configuration exceeds the 1 MiB limit");
            return new String(json, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON plugin configuration", e);
        }
    }

    private Object interpolate(Object value, Map<String, Object> context, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Plugin configuration nesting exceeds limit");
        if (value instanceof String text) return interpolateString(text, context);
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, child) -> {
                if (!(key instanceof String name)) throw new IllegalArgumentException("Plugin configuration keys must be strings");
                result.put(name, interpolate(child, context, depth + 1));
            });
            return result;
        }
        if (value instanceof List<?> source) {
            List<Object> result = new ArrayList<>(source.size());
            for (Object child : source) result.add(interpolate(child, context, depth + 1));
            return result;
        }
        return value;
    }

    private Object interpolateString(String text, Map<String, Object> context) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        if (!matcher.find()) {
            if (text.contains("${")) throw new IllegalArgumentException("Malformed context placeholder in plugin configuration");
            return text;
        }
        if (matcher.start() == 0 && matcher.end() == text.length()) return lookup(matcher.group(1), context);
        StringBuffer result = new StringBuffer();
        do {
            Object value = lookup(matcher.group(1), context);
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                try { value = objectMapper.writeValueAsString(value); }
                catch (Exception e) { throw new IllegalArgumentException("Unable to serialize interpolated value", e); }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        } while (matcher.find());
        matcher.appendTail(result);
        if (result.length() > 65_536) throw new IllegalArgumentException("Interpolated string exceeds 64 KiB limit");
        return result.toString();
    }

    private Object lookup(String expression, Map<String, Object> context) {
        String path = expression.trim();
        if (!PATH.matcher(path).matches()) throw new IllegalArgumentException("Invalid context path in plugin configuration");
        for (String part : path.split("\\.")) {
            if (SECRET_SEGMENT.matcher(part).matches()) throw new IllegalArgumentException("Secret values cannot be interpolated into plugin configuration");
        }
        Object current = context;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) current = null;
            else current = map.get(part);
            if (current == null) throw new IllegalArgumentException("Unable to resolve configuration context path: " + path);
        }
        return current;
    }
}
