package com.aryan.conduit.execution.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskQueueServiceTest {

    @Mock
    private RedisTemplate<String, Object> taskRedisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    @Mock
    private MapRecord<String, Object, Object> streamRecord;

    private TaskQueueService taskQueueService;

    @BeforeEach
    void setUp() {
        taskQueueService =
                new TaskQueueService(taskRedisTemplate);
    }

    @Test
    void shouldEnqueueTask() {

        TaskMessage message =
                new TaskMessage(
                        100L,
                        1010L,
                        10L
                );

        when(taskRedisTemplate.opsForStream())
                .thenReturn(streamOperations);

        taskQueueService.enqueue(message);

        verify(taskRedisTemplate)
                .opsForStream();

        verify(streamOperations)
                .add(any(Record.class));
    }

    @Test
    void shouldReadTasksFromStream() {

        TaskMessage message =
                new TaskMessage(
                        100L,
                        1010L,
                        10L
                );

        RecordId recordId =
                RecordId.of("1-0");

        when(taskRedisTemplate.hasKey(
                "conduit:task-stream"
        )).thenReturn(true);

        when(taskRedisTemplate.opsForStream())
                .thenReturn(streamOperations);

        when(streamRecord.getId())
                .thenReturn(recordId);

        when(streamRecord.getValue())
                .thenReturn(
                        Map.of(
                                "value",
                                message
                        )
                );

        Consumer consumer =
                Consumer.from(
                        "conduit-workers",
                        "worker-1"
                );

        StreamOffset<String> streamOffset =
                StreamOffset.create(
                        "conduit:task-stream",
                        ReadOffset.lastConsumed()
                );

        doReturn(List.of(streamRecord))
                .when(streamOperations)
                .read(
                        eq(consumer),
                        eq(streamOffset)
                );

        List<StreamMessage> result =
                taskQueueService.read("worker-1");

        assertEquals(
                1,
                result.size()
        );

        StreamMessage streamMessage =
                result.get(0);

        assertEquals(
                recordId,
                streamMessage.recordId()
        );

        assertEquals(
                message,
                streamMessage.taskMessage()
        );
    }

    @Test
    void shouldReturnEmptyListWhenStreamDoesNotExist() {

        when(taskRedisTemplate.hasKey(
                "conduit:task-stream"
        )).thenReturn(false);

        List<StreamMessage> result =
                taskQueueService.read("worker-1");

        assertEquals(
                0,
                result.size()
        );

        verify(taskRedisTemplate)
                .hasKey("conduit:task-stream");

        verify(taskRedisTemplate, never())
                .opsForStream();
    }

    @Test
    void shouldAcknowledgeTask() {

        RecordId recordId =
                RecordId.of("1-0");

        when(taskRedisTemplate.opsForStream())
                .thenReturn(streamOperations);

        taskQueueService.acknowledge(recordId);

        verify(streamOperations)
                .acknowledge(
                        eq("conduit:task-stream"),
                        eq("conduit-workers"),
                        eq(recordId)
                );
    }
}