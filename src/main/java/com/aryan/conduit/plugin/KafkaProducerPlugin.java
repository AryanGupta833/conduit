package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class KafkaProducerPlugin implements WorkflowPlugin {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata(
                "KAFKA_PRODUCER",
                "Kafka Producer",
                "Publish a message to an Apache Kafka topic.",
                "1.0.0",
                Map.of(
                        "type", "object",
                        "required", List.of("topic", "message"),
                        "properties", Map.of(
                                "topic", Map.of(
                                        "type", "string",
                                        "description", "Kafka topic to publish to"
                                ),
                                "key", Map.of(
                                        "type", "string",
                                        "description", "Optional Kafka message key"
                                ),
                                "message", Map.of(
                                        "description", "Message payload. Objects and arrays are serialized as JSON."
                                ),
                                "timeoutSeconds", Map.of(
                                        "type", "integer",
                                        "minimum", 1,
                                        "default", 30,
                                        "description", "Maximum time to wait for Kafka acknowledgement"
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
                    "KAFKA_PRODUCER configuration is required"
            );
        }

        requireNonBlank(
                configuration,
                "topic",
                "KAFKA_PRODUCER requires a non-empty 'topic'"
        );

        if (!configuration.containsKey("message")) {
            throw new PluginConfigurationException(
                    "KAFKA_PRODUCER requires 'message'"
            );
        }

        Object timeout = configuration.get("timeoutSeconds");

        if (timeout != null
                && (!(timeout instanceof Number number)
                || number.intValue() <= 0)) {

            throw new PluginConfigurationException(
                    "KAFKA_PRODUCER 'timeoutSeconds' must be a positive integer"
            );
        }

        Object key = configuration.get("key");

        if (key != null && !(key instanceof String)) {
            throw new PluginConfigurationException(
                    "KAFKA_PRODUCER 'key' must be a string"
            );
        }
    }

    @Override
    public PluginResult execute(PluginContext context) {

        validateConfiguration(context.configuration());

        Map<String, Object> configuration = context.configuration();

        String topic = String.valueOf(configuration.get("topic"));

        String key = configuration.get("key") == null
                ? null
                : String.valueOf(configuration.get("key"));

        Object message = configuration.get("message");

        int timeoutSeconds = configuration.containsKey("timeoutSeconds")
                ? ((Number) configuration.get("timeoutSeconds")).intValue()
                : 30;

        try {
            Object payload = preparePayload(message);

            var future = key == null
                    ? kafkaTemplate.send(topic, payload)
                    : kafkaTemplate.send(topic, key, payload);

            var result = future.get(timeoutSeconds, TimeUnit.SECONDS);

            return PluginResult.builder()
                    .success(true)
                    .output("Message published successfully")
                    .metadata(Map.of(
                            "operation", "PUBLISH",
                            "topic", topic,
                            "partition", result.getRecordMetadata().partition(),
                            "offset", result.getRecordMetadata().offset()
                    ))
                    .build();

        } catch (Exception ex) {

            String error = ex.getMessage() == null
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage();

            return PluginResult.builder()
                    .success(false)
                    .output(error)
                    .metadata(Map.of(
                            "operation", "PUBLISH",
                            "topic", topic
                    ))
                    .build();
        }
    }

    private Object preparePayload(Object message) throws Exception {

        if (message == null
                || message instanceof String
                || message instanceof Number
                || message instanceof Boolean) {

            return message;
        }

        return objectMapper.writeValueAsString(message);
    }

    private void requireNonBlank(
            Map<String, Object> configuration,
            String key,
            String message) {

        Object value = configuration.get(key);

        if (!(value instanceof String string) || string.isBlank()) {
            throw new PluginConfigurationException(message);
        }
    }
}