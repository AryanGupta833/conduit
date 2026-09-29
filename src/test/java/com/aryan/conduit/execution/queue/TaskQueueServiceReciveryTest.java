package com.aryan.conduit.execution.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskQueueServiceRecoveryTest {

    private static final String TASK_STREAM =
            "conduit:task-stream";

    private static final String CONSUMER_GROUP =
            "conduit-workers";

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private TaskQueueService taskQueueService;

    @BeforeEach
    void setUp() {

        taskQueueService =
                new TaskQueueService(redisTemplate);

        when(redisTemplate.hasKey(TASK_STREAM))
                .thenReturn(true);

        when(redisTemplate.opsForStream())
                .thenReturn(streamOperations);
    }

    @Test
    void shouldReturnEmptyWhenNoPendingMessages() {

        when(streamOperations.pending(
                eq(TASK_STREAM),
                eq(CONSUMER_GROUP),
                any(Range.class),
                anyLong()
        )).thenReturn(null);

        List<StreamMessage> result =
                taskQueueService.recover(
                        "recovery-worker",
                        Duration.ofSeconds(30),
                        10
                );

        assertEquals(
                0,
                result.size()
        );

        verify(streamOperations, never())
                .claim(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(Duration.class),
                        any(RecordId[].class)
                );
    }

    @Test
    void shouldNotClaimFreshMessages() {

        PendingMessage pendingMessage =
                mock(PendingMessage.class);

        when(pendingMessage
                .getElapsedTimeSinceLastDelivery())
                .thenReturn(Duration.ofSeconds(5));

        PendingMessages pendingMessages =
                new PendingMessages(
                        CONSUMER_GROUP,
                        List.of(pendingMessage)
                );

        when(streamOperations.pending(
                eq(TASK_STREAM),
                eq(CONSUMER_GROUP),
                any(Range.class),
                anyLong()
        )).thenReturn(pendingMessages);

        List<StreamMessage> result =
                taskQueueService.recover(
                        "recovery-worker",
                        Duration.ofSeconds(30),
                        10
                );

        assertEquals(
                0,
                result.size()
        );

        verify(streamOperations, never())
                .claim(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(Duration.class),
                        any(RecordId[].class)
                );
    }

    @Test
    void shouldClaimStaleMessages() {

        RecordId recordId =
                RecordId.of("1-0");

        PendingMessage pendingMessage =
                mock(PendingMessage.class);

        when(pendingMessage.getId())
                .thenReturn(recordId);

        when(pendingMessage
                .getElapsedTimeSinceLastDelivery())
                .thenReturn(Duration.ofSeconds(45));

        PendingMessages pendingMessages =
                new PendingMessages(
                        CONSUMER_GROUP,
                        List.of(pendingMessage)
                );

        when(streamOperations.pending(
                eq(TASK_STREAM),
                eq(CONSUMER_GROUP),
                any(Range.class),
                anyLong()
        )).thenReturn(pendingMessages);

        TaskMessage taskMessage =
                new TaskMessage(
                        100L,
                        101L,
                        10L
                );

        MapRecord<String, Object, Object> record =
                mock(MapRecord.class);

        when(record.getId())
                .thenReturn(recordId);

        when(record.getValue())
                .thenReturn(
                        Map.of(
                                "payload",
                                taskMessage
                        )
                );

        when(streamOperations.claim(
                eq(TASK_STREAM),
                eq(CONSUMER_GROUP),
                eq("recovery-worker"),
                eq(Duration.ofSeconds(30)),
                any(RecordId[].class)
        )).thenReturn(
                List.of(record)
        );

        List<StreamMessage> result =
                taskQueueService.recover(
                        "recovery-worker",
                        Duration.ofSeconds(30),
                        10
                );

        assertEquals(
                1,
                result.size()
        );

        assertEquals(
                recordId,
                result.get(0).recordId()
        );

        assertEquals(
                taskMessage,
                result.get(0).taskMessage()
        );

        verify(streamOperations)
                .claim(
                        eq(TASK_STREAM),
                        eq(CONSUMER_GROUP),
                        eq("recovery-worker"),
                        eq(Duration.ofSeconds(30)),
                        any(RecordId[].class)
                );
    }

    @Test
    void shouldScanPastFreshPendingMessagesToRecoverLaterStaleDeliveries() {
        PendingMessage freshOne = pending("1-0", Duration.ofSeconds(5));
        PendingMessage freshTwo = pending("2-0", Duration.ofSeconds(5));
        PendingMessage stale = pending("3-0", Duration.ofSeconds(45));
        PendingMessages firstPage = new PendingMessages(CONSUMER_GROUP, List.of(freshOne, freshTwo));
        PendingMessages secondPage = new PendingMessages(CONSUMER_GROUP, List.of(stale));
        when(streamOperations.pending(eq(TASK_STREAM), eq(CONSUMER_GROUP), any(Range.class), anyLong()))
                .thenReturn(firstPage, secondPage);
        when(streamOperations.claim(eq(TASK_STREAM), eq(CONSUMER_GROUP), eq("recovery-worker"),
                eq(Duration.ofSeconds(30)), any(RecordId[].class))).thenReturn(List.of());

        taskQueueService.recover("recovery-worker", Duration.ofSeconds(30), 2);

        verify(streamOperations, times(2)).pending(eq(TASK_STREAM), eq(CONSUMER_GROUP), any(Range.class), anyLong());
        verify(streamOperations).claim(eq(TASK_STREAM), eq(CONSUMER_GROUP), eq("recovery-worker"),
                eq(Duration.ofSeconds(30)), argThat(ids -> ids.length == 1 && ids[0].equals(RecordId.of("3-0"))));
    }

    private PendingMessage pending(String id, Duration elapsed) {
        PendingMessage message = mock(PendingMessage.class);
        when(message.getId()).thenReturn(RecordId.of(id));
        when(message.getIdAsString()).thenReturn(id);
        when(message.getElapsedTimeSinceLastDelivery()).thenReturn(elapsed);
        return message;
    }
}
