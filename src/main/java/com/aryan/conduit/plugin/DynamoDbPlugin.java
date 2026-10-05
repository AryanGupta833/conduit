package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.math.BigDecimal;
import java.util.*;

@Component
public class DynamoDbPlugin implements WorkflowPlugin {
    private static final int MAX_ITEM_BYTES = 350 * 1024;
    private final DynamoDbClient dynamo;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public DynamoDbPlugin(DynamoDbClient dynamo, com.fasterxml.jackson.databind.ObjectMapper mapper) { this.dynamo = dynamo; this.mapper = mapper; }

    @Override public PluginMetadata metadata() {
        return new PluginMetadata("AWS_DYNAMODB", "AWS DynamoDB", "Gets, puts, or deletes one item in a DynamoDB table. No scans or unbounded queries.", "1.0.0",
                Map.of("type", "object", "required", List.of("tableName", "operation"), "properties", Map.of(
                        "tableName", Map.of("type", "string"), "operation", Map.of("type", "string", "enum", List.of("GET_ITEM", "PUT_ITEM", "DELETE_ITEM")),
                        "key", Map.of("type", "object"), "item", Map.of("type", "object"))));
    }

    @Override public void validateConfiguration(Map<String, Object> c) {
        String table = SnsPublishPlugin.required(c, "tableName");
        if (table.length() > 255 || !table.matches("[A-Za-z0-9_.-]+")) throw new PluginConfigurationException("DynamoDB tableName is invalid");
        String operation = String.valueOf(c.get("operation")).toUpperCase(Locale.ROOT);
        if (!List.of("GET_ITEM", "PUT_ITEM", "DELETE_ITEM").contains(operation)) throw new PluginConfigurationException("DynamoDB operation must be GET_ITEM, PUT_ITEM, or DELETE_ITEM");
        Object payload = "PUT_ITEM".equals(operation) ? c.get("item") : c.get("key");
        if (!(payload instanceof Map<?, ?> values) || values.isEmpty()) throw new PluginConfigurationException("DynamoDB operation requires a non-empty item or key object");
        try { if (mapper.writeValueAsBytes(values).length > MAX_ITEM_BYTES) throw new PluginConfigurationException("DynamoDB item/key exceeds 350 KiB"); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new PluginConfigurationException("DynamoDB item is not JSON serializable", e); }
    }

    @Override public PluginResult execute(PluginContext context) {
        Map<String, Object> c = context.configuration(); validateConfiguration(c);
        String table = String.valueOf(c.get("tableName"));
        String operation = String.valueOf(c.get("operation")).toUpperCase(Locale.ROOT);
        try {
            Map<String, AttributeValue> values = toAttributes((Map<?, ?>) c.get("PUT_ITEM".equals(operation) ? "item" : "key"));
            return switch (operation) {
                case "PUT_ITEM" -> { dynamo.putItem(PutItemRequest.builder().tableName(table).item(values).build()); yield ok("Item written", Map.of("operation", operation)); }
                case "GET_ITEM" -> {
                    var response = dynamo.getItem(GetItemRequest.builder().tableName(table).key(values).consistentRead(Boolean.TRUE.equals(c.get("consistentRead"))).build());
                    yield ok(mapper.writeValueAsString(fromAttributes(response.item())), Map.of("operation", operation, "found", response.hasItem()));
                }
                case "DELETE_ITEM" -> {
                    var response = dynamo.deleteItem(DeleteItemRequest.builder().tableName(table).key(values).returnValues(ReturnValue.ALL_OLD).build());
                    yield ok(mapper.writeValueAsString(fromAttributes(response.attributes())), Map.of("operation", operation, "deleted", response.hasAttributes()));
                }
                default -> throw new IllegalStateException("Unsupported DynamoDB action");
            };
        } catch (Exception e) { return SnsPublishPlugin.failure(e); }
    }

    private Map<String, AttributeValue> toAttributes(Map<?, ?> values) {
        Map<String, AttributeValue> result = new LinkedHashMap<>();
        values.forEach((rawKey, value) -> {
            if (!(rawKey instanceof String key) || key.isBlank()) throw new PluginConfigurationException("DynamoDB attribute names must be non-empty strings");
            result.put(key, toAttribute(value));
        });
        return result;
    }
    private AttributeValue toAttribute(Object value) {
        if (value == null) return AttributeValue.builder().nul(true).build();
        if (value instanceof String s) return AttributeValue.builder().s(s).build();
        if (value instanceof Boolean b) return AttributeValue.builder().bool(b).build();
        if (value instanceof Number n) return AttributeValue.builder().n(new BigDecimal(n.toString()).toPlainString()).build();
        if (value instanceof List<?> list) return AttributeValue.builder().l(list.stream().map(this::toAttribute).toList()).build();
        if (value instanceof Map<?, ?> map) return AttributeValue.builder().m(toAttributes(map)).build();
        throw new PluginConfigurationException("DynamoDB values must be JSON-compatible");
    }
    private Map<String, Object> fromAttributes(Map<String, AttributeValue> values) {
        if (values == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, fromAttribute(value)));
        return result;
    }
    private Object fromAttribute(AttributeValue value) {
        if (value.s() != null) return value.s();
        if (value.n() != null) return new BigDecimal(value.n());
        if (value.bool() != null) return value.bool();
        if (Boolean.TRUE.equals(value.nul())) return null;
        if (value.m() != null && !value.m().isEmpty()) return fromAttributes(value.m());
        if (value.l() != null && !value.l().isEmpty()) return value.l().stream().map(this::fromAttribute).toList();
        if (value.hasSs()) return value.ss();
        if (value.hasNs()) return value.ns();
        if (value.hasBs()) return value.bs().stream().map(bytes -> Base64.getEncoder().encodeToString(bytes.asByteArray())).toList();
        return null;
    }
    private PluginResult ok(String output, Map<String, Object> metadata) { return PluginResult.builder().success(true).output(output).metadata(metadata).build(); }
}
