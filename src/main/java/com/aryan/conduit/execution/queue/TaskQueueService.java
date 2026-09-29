package com.aryan.conduit.execution.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class TaskQueueService {

    private static final String TASK_STREAM = "conduit:task-stream";
    private static final String CONSUMER_GROUP = "conduit-workers";
    private static final String PAYLOAD_FIELD = "payload";

    private final RedisTemplate<String, Object> taskRedisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public TaskQueueService(
            RedisTemplate<String, Object> taskRedisTemplate,
            ObjectMapper objectMapper
    ) {
        this.taskRedisTemplate = taskRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public TaskQueueService(
            RedisTemplate<String, Object> taskRedisTemplate
    ) {
        this.taskRedisTemplate = taskRedisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public void enqueue(TaskMessage taskMessage) {
        try {
            String json = objectMapper.writeValueAsString(taskMessage);

            Map<String, String> payload = Map.of(
                    PAYLOAD_FIELD,
                    json
            );

            taskRedisTemplate.opsForStream().add(
                    StreamRecords
                            .newRecord()
                            .ofMap(payload)
                            .withStreamKey(TASK_STREAM)
            );

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize task message",
                    e
            );
        }
    }

    public List<StreamMessage> read(String consumerName) {

        if (!ensureConsumerGroup()) {
            return List.of();
        }

        List<MapRecord<String, Object, Object>> records =
                taskRedisTemplate.opsForStream().read(
                        Consumer.from(CONSUMER_GROUP, consumerName),
                        StreamOffset.create(
                                TASK_STREAM,
                                ReadOffset.lastConsumed()
                        )
                );

        if (records == null || records.isEmpty()) {
            return List.of();
        }

        return records.stream()
                .map(this::toStreamMessage)
                .toList();
    }

    public List<StreamMessage> recover(
            String consumerName,
            Duration minIdleTime,
            int batchSize
    ) {

        if (!ensureConsumerGroup()) {
            return List.of();
        }

        PendingMessages pendingMessages =
                taskRedisTemplate.opsForStream().pending(
                        TASK_STREAM,
                        CONSUMER_GROUP,
                        Range.unbounded(),
                        batchSize
                );

        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return List.of();
        }

        List<RecordId> staleIds =
                pendingMessages.stream()
                        .filter(message ->
                                !message
                                        .getElapsedTimeSinceLastDelivery()
                                        .minus(minIdleTime)
                                        .isNegative()
                        )
                        .map(PendingMessage::getId)
                        .toList();

        if (staleIds.isEmpty()) {
            return List.of();
        }

        List<MapRecord<String, Object, Object>> claimedRecords =
                taskRedisTemplate.opsForStream().claim(
                        TASK_STREAM,
                        CONSUMER_GROUP,
                        consumerName,
                        minIdleTime,
                        staleIds.toArray(new RecordId[0])
                );

        if (claimedRecords == null || claimedRecords.isEmpty()) {
            return List.of();
        }

        return claimedRecords.stream()
                .map(this::toStreamMessage)
                .toList();
    }

    public void acknowledge(RecordId recordId) {
        taskRedisTemplate.opsForStream()
                .acknowledge(
                        TASK_STREAM,
                        CONSUMER_GROUP,
                        recordId
                );
    }

    public long streamEntryCount() {
        Long size = taskRedisTemplate.opsForStream().size(TASK_STREAM);
        return size == null ? 0 : size;
    }

    public long pendingCount() {
        if (!ensureConsumerGroup()) return 0;
        var pending = taskRedisTemplate.opsForStream().pending(TASK_STREAM, CONSUMER_GROUP);
        return pending == null ? 0 : pending.getTotalPendingMessages();
    }

    private boolean ensureConsumerGroup() {

        Boolean exists =
                taskRedisTemplate.hasKey(TASK_STREAM);

        if (!Boolean.TRUE.equals(exists)) {
            return false;
        }

        try {
            taskRedisTemplate.opsForStream().createGroup(
                    TASK_STREAM,
                    ReadOffset.from("0-0"),
                    CONSUMER_GROUP
            );
        } catch (DataAccessException ignored) {
            // Group already exists.
        }

        return true;
    }

    private StreamMessage toStreamMessage(
            MapRecord<String, Object, Object> record
    ) {

        Object payload = record.getValue().get(PAYLOAD_FIELD);

        if (payload == null) {
            throw new IllegalStateException(
                    "Task stream record does not contain a payload field"
            );
        }

        TaskMessage taskMessage =
                convertToTaskMessage(payload);

        return new StreamMessage(
                record.getId(),
                taskMessage
        );
    }

    private TaskMessage convertToTaskMessage(Object value) {

        if (value instanceof TaskMessage taskMessage) {
            return taskMessage;
        }

        if (value instanceof String json) {
            try {
                return objectMapper.readValue(
                        json,
                        TaskMessage.class
                );
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "Failed to deserialize task stream JSON payload: "
                                + json,
                        e
                );
            }
        }

        if (value instanceof Map<?, ?> map) {
            try {
                return objectMapper.convertValue(
                        map,
                        TaskMessage.class
                );
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Failed to convert task stream map payload",
                        e
                );
            }
        }

        throw new IllegalStateException(
                "Unexpected task stream payload type: "
                        + value.getClass().getName()
        );
    }
}
