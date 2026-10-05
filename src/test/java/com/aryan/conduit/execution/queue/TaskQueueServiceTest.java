package com.aryan.conduit.execution.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                .add(
                        argThat(record -> {
                            Object payload =
                                    record.getValue()
                                            .get("payload");

                            return payload != null
                                    && payload.toString().contains(
                                    "\"workflowExecutionId\":100"
                            )
                                    && payload.toString().contains(
                                    "\"taskExecutionId\":1010"
                            )
                                    && payload.toString().contains(
                                    "\"taskNodeId\":10"
                            );
                        })
                );
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

        /*
         * Redis now stores the TaskMessage as JSON
         * under the "payload" field.
         */
        String jsonPayload =
                """
                {"workflowExecutionId":100,"taskExecutionId":1010,"taskNodeId":10}
                """;

        when(streamRecord.getValue())
                .thenReturn(
                        Map.of(
                                "payload",
                                jsonPayload
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
                        any(StreamReadOptions.class),
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
    void malformedPayloadIsDeadLetteredWithoutBlockingValidBatchEntries() {
        MapRecord<String, Object, Object> malformed = mock(MapRecord.class);
        MapRecord<String, Object, Object> valid = mock(MapRecord.class);
        RecordId malformedId = RecordId.of("1-0");
        RecordId validId = RecordId.of("2-0");
        when(taskRedisTemplate.hasKey("conduit:task-stream")).thenReturn(true);
        when(taskRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(malformed.getId()).thenReturn(malformedId);
        when(malformed.getValue()).thenReturn(Map.of("payload", "not-json"));
        when(valid.getId()).thenReturn(validId);
        when(valid.getValue()).thenReturn(Map.of("payload",
                "{\"workflowExecutionId\":100,\"taskExecutionId\":1010,\"taskNodeId\":10}"));
        when(streamOperations.acknowledge("conduit:task-stream", "conduit-workers", malformedId))
                .thenReturn(1L);
        StreamOffset<String> offset = StreamOffset.create("conduit:task-stream", ReadOffset.lastConsumed());
        doReturn(List.of(malformed, valid)).when(streamOperations).read(
                eq(Consumer.from("conduit-workers", "worker-1")),
                any(StreamReadOptions.class), eq(offset));

        List<StreamMessage> result = taskQueueService.read("worker-1");

        assertEquals(1, result.size());
        assertEquals(validId, result.get(0).recordId());
        verify(streamOperations).add(argThat(record ->
                record.getStream().equals("conduit:task-stream:dead-letter")
                        && record.getValue().get("sourceRecordId").equals("1-0")));
        verify(streamOperations).acknowledge("conduit:task-stream", "conduit-workers", malformedId);
        verify(streamOperations).delete("conduit:task-stream", malformedId);
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
    void shouldNotHideRedisFailureWhileCreatingConsumerGroup() {
        when(taskRedisTemplate.hasKey("conduit:task-stream")).thenReturn(true);
        when(taskRedisTemplate.opsForStream()).thenReturn(streamOperations);
        doThrow(new DataAccessResourceFailureException("connection refused"))
                .when(streamOperations).createGroup("conduit:task-stream", ReadOffset.from("0-0"), "conduit-workers");

        org.junit.jupiter.api.Assertions.assertThrows(
                DataAccessResourceFailureException.class,
                () -> taskQueueService.read("worker-1"));
        verify(streamOperations, never()).read(
                any(org.springframework.data.redis.connection.stream.Consumer.class),
                any(StreamReadOptions.class),
                any(StreamOffset.class));
    }

    @Test
    void shouldAcknowledgeTask() {

        RecordId recordId =
                RecordId.of("1-0");

        when(taskRedisTemplate.opsForStream())
                .thenReturn(streamOperations);
        when(streamOperations.acknowledge(
                eq("conduit:task-stream"), eq("conduit-workers"), eq(recordId)))
                .thenReturn(1L);

        taskQueueService.acknowledge(recordId);

        verify(streamOperations)
                .acknowledge(
                        eq("conduit:task-stream"),
                        eq("conduit-workers"),
                        eq(recordId)
                );
        verify(streamOperations).delete("conduit:task-stream", recordId);
    }

    @Test
    void shouldNotDeleteStreamEntryWhenAckWasNotConfirmed() {
        RecordId recordId = RecordId.of("1-0");
        when(taskRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.acknowledge("conduit:task-stream", "conduit-workers", recordId))
                .thenReturn(0L);

        taskQueueService.acknowledge(recordId);

        verify(streamOperations, never()).delete(anyString(), any(RecordId.class));
    }
}
