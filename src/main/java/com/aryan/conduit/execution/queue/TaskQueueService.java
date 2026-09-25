package com.aryan.conduit.execution.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskQueueService {

    private static final String TASK_STREAM =
            "conduit:task-stream";

    private static final String CONSUMER_GROUP =
            "conduit-workers";

    private final RedisTemplate<String, Object> taskRedisTemplate;

    public void enqueue(
            TaskMessage taskMessage
    ) {

        taskRedisTemplate
                .opsForStream()
                .add(
                        StreamRecords
                                .newRecord()
                                .ofObject(taskMessage)
                                .withStreamKey(
                                        TASK_STREAM
                                )
                );
    }

    public List<StreamMessage> read(
            String consumerName
    ) {

        if (!ensureConsumerGroup()) {
            return List.of();
        }

        List<MapRecord<String, Object, Object>> records =
                taskRedisTemplate
                        .opsForStream()
                        .read(
                                Consumer.from(
                                        CONSUMER_GROUP,
                                        consumerName
                                ),
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

    public void acknowledge(
            RecordId recordId
    ) {

        taskRedisTemplate
                .opsForStream()
                .acknowledge(
                        TASK_STREAM,
                        CONSUMER_GROUP,
                        recordId
                );
    }

    private boolean ensureConsumerGroup() {

        Boolean exists =
                taskRedisTemplate.hasKey(
                        TASK_STREAM
                );

        if (!Boolean.TRUE.equals(exists)) {
            return false;
        }

        try {

            taskRedisTemplate
                    .opsForStream()
                    .createGroup(
                            TASK_STREAM,
                            ReadOffset.from("0-0"),
                            CONSUMER_GROUP
                    );

        } catch (DataAccessException ignored) {
            /*
             * The consumer group already exists.
             */
        }

        return true;
    }

    private StreamMessage toStreamMessage(
            MapRecord<String, Object, Object> record
    ) {

        Object value =
                record.getValue()
                        .values()
                        .stream()
                        .findFirst()
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Task stream record contains no value"
                                )
                        );

        if (!(value instanceof TaskMessage taskMessage)) {

            throw new IllegalStateException(
                    "Unexpected task stream payload type: "
                            + value.getClass().getName()
            );
        }

        return new StreamMessage(
                record.getId(),
                taskMessage
        );
    }
}