package com.aryan.conduit.plugin.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Serializable result of one plugin invocation. */
public record PluginResult(boolean success, String output,
                           Map<String, Object> variables, Map<String, Object> metadata) {
    public PluginResult {
        variables = immutableCopy(variables);
        metadata = immutableCopy(metadata);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        return values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public Map<String, Object> getVariables() { return variables; }
    public Map<String, Object> getMetadata() { return metadata; }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean success;
        private String output;
        private Map<String, Object> variables = new LinkedHashMap<>();
        private Map<String, Object> metadata = new LinkedHashMap<>();
        public Builder success(boolean value) { success = value; return this; }
        public Builder output(String value) { output = value; return this; }
        public Builder variables(Map<String, Object> value) { variables = value; return this; }
        public Builder metadata(Map<String, Object> value) { metadata = value; return this; }
        public PluginResult build() { return new PluginResult(success, output, variables, metadata); }
    }
}
