package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaProducerPluginTest {

    private KafkaTemplate<String, Object> kafkaTemplate;
    private KafkaProducerPlugin plugin;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);

        plugin = new KafkaProducerPlugin(
                kafkaTemplate,
                new ObjectMapper()
        );
    }

    @Test
    void metadataIsCorrect() {

        var metadata = plugin.metadata();

        assertEquals("KAFKA_PRODUCER", metadata.type());
        assertEquals("Kafka Producer", metadata.displayName());
        assertEquals("1.0.0", metadata.version());
        assertNotNull(metadata.configurationSchema());
    }

    @Test
    void missingConfigurationIsRejected() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(null)
        );
    }

    @Test
    void missingTopicIsRejected() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of("message", "hello")
                )
        );
    }

    @Test
    void blankTopicIsRejected() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "topic", "   ",
                                "message", "hello"
                        )
                )
        );
    }

    @Test
    void missingMessageIsRejected() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of("topic", "test-topic")
                )
        );
    }

    @Test
    void invalidTimeoutIsRejected() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "topic", "test-topic",
                                "message", "hello",
                                "timeoutSeconds", 0
                        )
                )
        );
    }

    @Test
    void invalidKeyIsRejected() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "topic", "test-topic",
                                "message", "hello",
                                "key", 123
                        )
                )
        );
    }

    @Test
    void publishesStringMessage() {

        SendResult<String, Object> sendResult = mock(SendResult.class);

        var recordMetadata = new org.apache.kafka.clients.producer.RecordMetadata(
                new org.apache.kafka.common.TopicPartition(
                        "test-topic", 2
                ),
                10,
                20,
                System.currentTimeMillis(),
                0L,
                0,
                0
        );

        when(sendResult.getRecordMetadata())
                .thenReturn(recordMetadata);

        CompletableFuture<SendResult<String, Object>> future =
                CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(
                eq("test-topic"),
                eq("hello")
        )).thenReturn(future);

        PluginContext context = new PluginContext(
                "publish-task",
                30,
                Map.of(
                        "topic", "test-topic",
                        "message", "hello"
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());
        assertEquals("Message published successfully", result.getOutput());

        verify(kafkaTemplate)
                .send("test-topic", "hello");
    }

    @Test
    void publishesMessageWithKey() {

        SendResult<String, Object> sendResult = mock(SendResult.class);

        var recordMetadata = new org.apache.kafka.clients.producer.RecordMetadata(
                new org.apache.kafka.common.TopicPartition(
                        "test-topic", 1
                ),
                5,
                15,
                System.currentTimeMillis(),
                0L,
                0,
                0
        );

        when(sendResult.getRecordMetadata())
                .thenReturn(recordMetadata);

        when(kafkaTemplate.send(
                eq("test-topic"),
                eq("event-key"),
                eq("hello")
        )).thenReturn(
                CompletableFuture.completedFuture(sendResult)
        );

        PluginContext context = new PluginContext(
                "publish-task",
                30,
                Map.of(
                        "topic", "test-topic",
                        "key", "event-key",
                        "message", "hello"
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        verify(kafkaTemplate)
                .send("test-topic", "event-key", "hello");
    }

    @Test
    void serializesObjectMessageAsJson() {

        SendResult<String, Object> sendResult = mock(SendResult.class);

        var recordMetadata = new org.apache.kafka.clients.producer.RecordMetadata(
                new org.apache.kafka.common.TopicPartition(
                        "events", 0
                ),
                0,
                1,
                System.currentTimeMillis(),
                0L,
                0,
                0
        );

        when(sendResult.getRecordMetadata())
                .thenReturn(recordMetadata);

        when(kafkaTemplate.send(
                eq("events"),
                anyString()
        )).thenReturn(
                CompletableFuture.completedFuture(sendResult)
        );

        Map<String, Object> message = Map.of(
                "event", "TEST",
                "value", 42
        );

        PluginContext context = new PluginContext(
                "publish-task",
                30,
                Map.of(
                        "topic", "events",
                        "message", message
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        ArgumentCaptor<String> payloadCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(kafkaTemplate).send(
                eq("events"),
                payloadCaptor.capture()
        );

        String payload = payloadCaptor.getValue();

        assertTrue(payload.contains("\"event\":\"TEST\""));
        assertTrue(payload.contains("\"value\":42"));
    }

    @Test
    void kafkaFailureReturnsFailedPluginResult() {

        CompletableFuture<SendResult<String, Object>> future =
                new CompletableFuture<>();

        future.completeExceptionally(
                new RuntimeException("Kafka unavailable")
        );

        when(kafkaTemplate.send(
                eq("test-topic"),
                eq("hello")
        )).thenReturn(future);

        PluginContext context = new PluginContext(
                "publish-task",
                30,
                Map.of(
                        "topic", "test-topic",
                        "message", "hello"
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("Kafka unavailable"));
    }
}