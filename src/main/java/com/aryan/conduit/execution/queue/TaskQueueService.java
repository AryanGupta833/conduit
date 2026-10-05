package com.aryan.conduit.execution.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TaskQueueService {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueService.class);

    private static final String DEFAULT_TASK_STREAM = "conduit:task-stream";
    private static final String DEFAULT_CONSUMER_GROUP = "conduit-workers";
    private static final String DEAD_LETTER_STREAM_SUFFIX = ":dead-letter";
    private static final String PAYLOAD_FIELD = "payload";
    private static final int MAX_DEAD_LETTER_FIELD_CHARS = 4096;

    private final RedisTemplate<String, Object> taskRedisTemplate;
    private final ObjectMapper objectMapper;
    private final String taskStream;
    private final String consumerGroup;
    private final int batchSize;
    private final AtomicBoolean redisUnavailable = new AtomicBoolean();
    private volatile long lastRedisWarningNanos;

    @Autowired
    public TaskQueueService(
            RedisTemplate<String, Object> taskRedisTemplate,
            ObjectMapper objectMapper,
            @Value("${conduit.execution.queue.stream:conduit:task-stream}") String taskStream,
            @Value("${conduit.execution.queue.group:conduit-workers}") String consumerGroup,
            @Value("${conduit.execution.queue.batch-size:10}") int batchSize
    ) {
        this.taskRedisTemplate = taskRedisTemplate;
        this.objectMapper = objectMapper;
        this.taskStream = taskStream;
        this.consumerGroup = consumerGroup;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    public TaskQueueService(
            RedisTemplate<String, Object> taskRedisTemplate
    ) {
        this(taskRedisTemplate, new ObjectMapper(), DEFAULT_TASK_STREAM, DEFAULT_CONSUMER_GROUP, 10);
    }

    public TaskQueueService(RedisTemplate<String, Object> taskRedisTemplate, ObjectMapper objectMapper) {
        this(taskRedisTemplate, objectMapper, DEFAULT_TASK_STREAM, DEFAULT_CONSUMER_GROUP, 10);
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
                            .withStreamKey(taskStream)
            );

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize task message",
                    e
            );
        }
    }

    public List<StreamMessage> read(String consumerName) {
        try {
            if (!ensureConsumerGroup()) return List.of();
            List<MapRecord<String, Object, Object>> records = taskRedisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    StreamReadOptions.empty().count(batchSize),
                    StreamOffset.create(taskStream, ReadOffset.lastConsumed()));
            redisAvailable();
            if (records == null || records.isEmpty()) return List.of();
            return decodeRecords(records);
        } catch (DataAccessException unavailable) {
            redisUnavailable(unavailable);
            return List.of();
        }
    }

    public List<StreamMessage> recover(
            String consumerName,
            Duration minIdleTime,
            int batchSize
    ) {
        try {
            if (!ensureConsumerGroup()) return List.of();
            int pageSize = Math.max(batchSize, 1);
            List<StreamMessage> recovered = new ArrayList<>();
            String afterId = null;
            while (true) {
            Range<String> range = afterId == null
                    ? Range.unbounded()
                    : Range.leftOpen(afterId, "+");
            PendingMessages pendingMessages = taskRedisTemplate.opsForStream().pending(
                    taskStream,
                    consumerGroup,
                    range,
                    pageSize
            );

            if (pendingMessages == null || pendingMessages.isEmpty()) break;

            List<PendingMessage> page = pendingMessages.stream().toList();
            List<RecordId> staleIds = page.stream()
                    .filter(message -> !message.getElapsedTimeSinceLastDelivery().minus(minIdleTime).isNegative())
                    .limit(Math.max(0, batchSize - recovered.size()))
                    .map(PendingMessage::getId)
                    .toList();

            if (!staleIds.isEmpty()) {
                List<MapRecord<String, Object, Object>> claimedRecords = taskRedisTemplate.opsForStream().claim(
                        taskStream,
                        consumerGroup,
                        consumerName,
                        minIdleTime,
                        staleIds.toArray(new RecordId[0])
                );
                if (claimedRecords != null) {
                    recovered.addAll(decodeRecords(claimedRecords));
                }
            }

            if (recovered.size() >= batchSize) break;

            if (page.size() < pageSize) break;
            afterId = page.get(page.size() - 1).getIdAsString();
            }
            redisAvailable();
            return recovered;
        } catch (DataAccessException unavailable) {
            redisUnavailable(unavailable);
            return List.of();
        }
    }

    public void acknowledge(RecordId recordId) {
        Long acknowledged = taskRedisTemplate.opsForStream()
                .acknowledge(taskStream, consumerGroup, recordId);
        // Conduit owns this work stream and uses one shared consumer group.
        // Remove completed entries after ACK so Redis memory does not grow
        // forever. Never delete an entry unless Redis confirms it was ACKed.
        if (acknowledged != null && acknowledged > 0) {
            try {
                taskRedisTemplate.opsForStream().delete(taskStream, recordId);
            } catch (DataAccessException cleanupFailure) {
                // The durable task result is already committed; cleanup failure
                // must not turn it into a failed task or obscure ACK success.
                log.warn("Unable to remove acknowledged task stream entry {}", recordId, cleanupFailure);
            }
        }
    }

    public long streamEntryCount() {
        Long size = taskRedisTemplate.opsForStream().size(taskStream);
        return size == null ? 0 : size;
    }

    public long pendingCount() {
        if (!ensureConsumerGroup()) return 0;
        var pending = taskRedisTemplate.opsForStream().pending(taskStream, consumerGroup);
        return pending == null ? 0 : pending.getTotalPendingMessages();
    }

    /** Redis consumer-group operational state. This deliberately does not infer queue lag from stream length. */
    public QueueOperationsResponse operations() {
        if (!ensureConsumerGroup()) return new QueueOperationsResponse(taskStream, consumerGroup, streamEntryCount(), 0, null, List.of());
        var groups = taskRedisTemplate.opsForStream().groups(taskStream);
        var group = groups == null ? null : groups.stream()
                .filter(value -> consumerGroup.equals(value.groupName())).findFirst().orElse(null);
        if (group == null) return new QueueOperationsResponse(taskStream, consumerGroup, streamEntryCount(), 0, null, List.of());
        var consumerInfo = taskRedisTemplate.opsForStream().consumers(taskStream, consumerGroup);
        List<QueueOperationsResponse.ConsumerState> consumers = consumerInfo == null ? List.of() : consumerInfo.stream()
                .map(value -> new QueueOperationsResponse.ConsumerState(value.consumerName(), value.pendingCount(), value.idleTimeMs())).toList();
        return new QueueOperationsResponse(taskStream, consumerGroup, streamEntryCount(),
                group.pendingCount() == null ? 0 : group.pendingCount(), group.lastDeliveredId(), consumers);
    }

    public String streamName() { return taskStream; }
    public String groupName() { return consumerGroup; }

    private boolean ensureConsumerGroup() {

        Boolean exists =
                taskRedisTemplate.hasKey(taskStream);
        redisAvailable();

        if (!Boolean.TRUE.equals(exists)) {
            return false;
        }

        try {
            taskRedisTemplate.opsForStream().createGroup(
                    taskStream,
                    ReadOffset.from("0-0"),
                    consumerGroup
            );
        } catch (DataAccessException failure) {
            if (!isConsumerGroupAlreadyExists(failure)) throw failure;
        }

        return true;
    }

    private boolean isConsumerGroupAlreadyExists(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("BUSYGROUP")
                    || message.toLowerCase(java.util.Locale.ROOT).contains("consumer group name already exists"))) {
                return true;
            }
        }
        return false;
    }

    private void redisUnavailable(DataAccessException failure) {
        long now = System.nanoTime();
        if (redisUnavailable.compareAndSet(false, true)
                || now - lastRedisWarningNanos > java.time.Duration.ofSeconds(30).toNanos()) {
            lastRedisWarningNanos = now;
            Throwable cause = failure.getMostSpecificCause();
            log.warn("Redis task queue is unavailable; polling will retry and deliveries remain pending: {}",
                    cause == null ? failure.getMessage() : cause.getMessage());
        }
    }

    private void redisAvailable() {
        if (redisUnavailable.compareAndSet(true, false)) {
            log.info("Redis task queue connection recovered");
        }
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
        if (taskMessage.workflowExecutionId() == null || taskMessage.taskExecutionId() == null
                || taskMessage.taskNodeId() == null || taskMessage.workflowExecutionId() <= 0
                || taskMessage.taskExecutionId() <= 0 || taskMessage.taskNodeId() <= 0) {
            throw new IllegalStateException("Task stream payload is missing valid identifiers");
        }

        return new StreamMessage(
                record.getId(),
                taskMessage
        );
    }

    private List<StreamMessage> decodeRecords(List<MapRecord<String, Object, Object>> records) {
        List<StreamMessage> decoded = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            try {
                decoded.add(toStreamMessage(record));
            } catch (RuntimeException malformed) {
                quarantine(record, malformed);
            }
        }
        return decoded;
    }

    /** Persist poison records separately before removing them from the work PEL. */
    private void quarantine(MapRecord<String, Object, Object> record, RuntimeException cause) {
        Map<String, String> fields = new LinkedHashMap<>();
        record.getValue().forEach((key, value) -> fields.put(
                String.valueOf(key), bounded(String.valueOf(value))));
        fields.put("sourceRecordId", record.getId().getValue());
        fields.put("error", bounded(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
        String deadLetterStream = taskStream + DEAD_LETTER_STREAM_SUFFIX;
        taskRedisTemplate.opsForStream().add(StreamRecords.newRecord().ofMap(fields).withStreamKey(deadLetterStream));
        log.error("Moved malformed task stream record {} to {}", record.getId(), deadLetterStream, cause);
        acknowledge(record.getId());
    }

    private String bounded(String value) {
        if (value == null) return "";
        return value.length() <= MAX_DEAD_LETTER_FIELD_CHARS
                ? value : value.substring(0, MAX_DEAD_LETTER_FIELD_CHARS);
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
