package com.aryan.conduit.execution.worker;

import com.aryan.conduit.execution.queue.StreamMessage;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskRecoveryServiceTest {

    @Mock
    private TaskQueueService taskQueueService;

    @Mock
    private TaskWorker taskWorker;

    @InjectMocks
    private TaskRecoveryService taskRecoveryService;

    @Test
    void shouldDoNothingWhenNoMessagesAreRecovered() {

        ReflectionTestUtils.setField(
                taskRecoveryService,
                "idleTimeoutMs",
                30000L
        );

        ReflectionTestUtils.setField(
                taskRecoveryService,
                "batchSize",
                10
        );

        when(taskQueueService.recover(
                any(String.class),
                eq(Duration.ofMillis(30000)),
                eq(10)
        )).thenReturn(List.of());

        taskRecoveryService.recover();

        verify(taskQueueService).recover(
                any(String.class),
                eq(Duration.ofMillis(30000)),
                eq(10)
        );

        verify(taskWorker, never())
                .process(any(StreamMessage.class));
    }

    @Test
    void shouldProcessRecoveredMessage() {

        ReflectionTestUtils.setField(
                taskRecoveryService,
                "idleTimeoutMs",
                30000L
        );

        ReflectionTestUtils.setField(
                taskRecoveryService,
                "batchSize",
                10
        );

        TaskMessage taskMessage =
                new TaskMessage(
                        100L,
                        101L,
                        10L
                );

        StreamMessage streamMessage =
                new StreamMessage(
                        null,
                        taskMessage
                );

        when(taskQueueService.recover(
                any(String.class),
                eq(Duration.ofMillis(30000)),
                eq(10)
        )).thenReturn(
                List.of(streamMessage)
        );

        taskRecoveryService.recover();

        verify(taskWorker)
                .process(streamMessage);
    }

    @Test
    void shouldProcessAllRecoveredMessages() {

        ReflectionTestUtils.setField(
                taskRecoveryService,
                "idleTimeoutMs",
                30000L
        );

        ReflectionTestUtils.setField(
                taskRecoveryService,
                "batchSize",
                10
        );

        TaskMessage taskMessage1 =
                new TaskMessage(
                        100L,
                        101L,
                        10L
                );

        TaskMessage taskMessage2 =
                new TaskMessage(
                        100L,
                        102L,
                        11L
                );

        TaskMessage taskMessage3 =
                new TaskMessage(
                        100L,
                        103L,
                        12L
                );

        StreamMessage message1 =
                new StreamMessage(null, taskMessage1);

        StreamMessage message2 =
                new StreamMessage(null, taskMessage2);

        StreamMessage message3 =
                new StreamMessage(null, taskMessage3);

        when(taskQueueService.recover(
                any(String.class),
                eq(Duration.ofMillis(30000)),
                eq(10)
        )).thenReturn(
                List.of(
                        message1,
                        message2,
                        message3
                )
        );

        taskRecoveryService.recover();

        verify(taskWorker)
                .process(message1);

        verify(taskWorker)
                .process(message2);

        verify(taskWorker)
                .process(message3);
    }
}