package com.aryan.conduit.plugin.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Prevents credentials from being stored as ordinary workflow configuration values. */
public final class InlineSecretConfigurationGuard {
    private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i).*(password|secret|token|credential|private.?key|access.?key|api.?key|authorization|cookie).*");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)https?://[^/@\\s]+:[^/@\\s]+@");
    private static final Pattern URL_QUERY_SECRET = Pattern.compile("(?i)[?&](?:password|passwd|secret|token|api[_-]?key|access[_-]?key|authorization|credential)=([^&#\\s]+)");
    private static final Pattern SECRET_ENV_NAME = Pattern.compile("(?i).*(password|secret|token|credential|private.?key|access.?key|api.?key|authorization|cookie).*");
    private static final int MAX_DEPTH = 32;
    private InlineSecretConfigurationGuard() { }

    public static void validateJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return;
        try {
            Object value = mapper.readValue(json, new TypeReference<>() { });
            if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Plugin configuration must be a JSON object");
            validate(value, 0);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Plugin configuration must be valid JSON", error);
        }
    }

    public static String redactJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return json;
        try {
            Object value = mapper.readValue(json, new TypeReference<>() { });
            return mapper.writeValueAsString(redact(value, 0));
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static void validate(Object value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Plugin configuration nesting exceeds limit");
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("$credential")) {
                if (map.size() != 1 || !(map.get("$credential") instanceof String name) || name.isBlank())
                    throw new IllegalArgumentException("Credential references must use only a non-empty $credential value");
                return;
            }
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Plugin configuration keys must be strings");
                Object child = entry.getValue();
                if (SENSITIVE_KEY.matcher(key).matches() && isNonEmptySecret(child) && !isCredentialReference(child))
                    throw new IllegalArgumentException("Sensitive plugin configuration fields must use a {\"$credential\":\"name\"} reference");
                validate(child, depth + 1);
            }
            Object variableName = map.get("name");
            Object variableValue = map.get("value");
            if (variableName instanceof String name && SECRET_ENV_NAME.matcher(name).matches()
                    && isNonEmptySecret(variableValue) && !isCredentialReference(variableValue))
                throw new IllegalArgumentException("Credential-like name/value configuration must use a credential reference");
        } else if (value instanceof List<?> list) {
            for (Object child : list) validate(child, depth + 1);
        } else if (value instanceof String text) {
            if (URL_CREDENTIALS.matcher(text).find() || URL_QUERY_SECRET.matcher(text).find())
                throw new IllegalArgumentException("URLs containing embedded credentials are not allowed; use a credential reference");
        }
    }

    private static boolean isNonEmptySecret(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        if (value instanceof List<?> list) return !list.isEmpty();
        return true;
    }

    private static Object redact(Object value, int depth) {
        if (depth > MAX_DEPTH) return "[REDACTED]";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String name)) return;
                if (name.equals("$credential")) safe.put(name, child);
                else if (SENSITIVE_KEY.matcher(name).matches() && isNonEmptySecret(child) && !isCredentialReference(child)) safe.put(name, "");
                else safe.put(name, redact(child, depth + 1));
            });
            return safe;
        }
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>(list.size());
            list.forEach(child -> safe.add(redact(child, depth + 1)));
            return safe;
        }
        if (value instanceof String text) return URL_QUERY_SECRET.matcher(URL_CREDENTIALS.matcher(text).replaceAll("https://[REDACTED]@")).replaceAll("$1[REDACTED]");
        return value;
    }

    private static boolean isCredentialReference(Object value) {
        return value instanceof Map<?, ?> reference && reference.size() == 1
                && reference.get("$credential") instanceof String name && !name.isBlank();
    }
}
