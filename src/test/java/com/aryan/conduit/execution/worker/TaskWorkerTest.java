package com.aryan.conduit.execution.worker;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.queue.StreamMessage;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.queue.TaskResult;
import com.aryan.conduit.execution.queue.TaskResultHandler;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.service.TaskRunnerService;
import com.aryan.conduit.workflow.entity.TaskNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.RecordId;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskWorkerTest {

    @Mock
    private TaskQueueService taskQueueService;

    @Mock
    private TaskExecutionRepository taskExecutionRepository;

    @Mock
    private TaskRunnerService taskRunnerService;

    @Mock
    private TaskResultHandler taskResultHandler;

    private TaskWorker taskWorker;

    @BeforeEach
    void setUp() {

        taskWorker =
                new TaskWorker(
                        taskQueueService,
                        taskExecutionRepository,
                        taskRunnerService,
                        taskResultHandler
                );
    }

    private TaskExecution createTaskExecution() {

        TaskNode taskNode =
                TaskNode.builder()
                        .id(10L)
                        .name("Test Task")
                        .maxRetries(1)
                        .timeoutSeconds(30)
                        .build();

        return TaskExecution.builder()
                .id(1010L)
                .taskNode(taskNode)
                .status(TaskExecutionStatus.QUEUED)
                .retryCount(0)
                .idempotencyKey("100:10")
                .build();
    }

    private StreamMessage createStreamMessage() {

        TaskMessage taskMessage =
                new TaskMessage(
                        100L,
                        1010L,
                        10L
                );

        return new StreamMessage(
                RecordId.of("1-0"),
                taskMessage
        );
    }

    @Test
    void shouldProcessTask() throws InterruptedException {

        TaskExecution taskExecution =
                createTaskExecution();

        StreamMessage streamMessage =
                createStreamMessage();

        when(taskExecutionRepository.findById(1010L))
                .thenReturn(
                        Optional.of(taskExecution)
                );

        when(taskRunnerService.executeWithRetry(
                1010L,
                1,
                30
        )).thenReturn(new HashMap<>());

        taskWorker.process(streamMessage);

        assertEquals(
                TaskExecutionStatus.RUNNING,
                taskExecution.getStatus()
        );

        verify(taskExecutionRepository)
                .save(taskExecution);

        verify(taskRunnerService)
                .executeWithRetry(
                        1010L,
                        1,
                        30
                );

        verify(taskResultHandler)
                .handle(any(TaskResult.class));

        verify(taskQueueService)
                .acknowledge(
                        streamMessage.recordId()
                );
    }

    @Test
    void shouldConsumeTaskFromQueue() throws InterruptedException {

        StreamMessage streamMessage =
                createStreamMessage();

        TaskExecution taskExecution =
                createTaskExecution();

        when(taskQueueService.read(anyString()))
                .thenReturn(
                        List.of(streamMessage)
                );

        when(taskExecutionRepository.findById(1010L))
                .thenReturn(
                        Optional.of(taskExecution)
                );

        when(taskRunnerService.executeWithRetry(
                1010L,
                1,
                30
        )).thenReturn(new HashMap<>());

        taskWorker.poll();

        verify(taskQueueService)
                .read(anyString());

        verify(taskExecutionRepository)
                .findById(1010L);

        verify(taskRunnerService)
                .executeWithRetry(
                        1010L,
                        1,
                        30
                );

        verify(taskResultHandler)
                .handle(any(TaskResult.class));

        verify(taskQueueService)
                .acknowledge(
                        streamMessage.recordId()
                );
    }

    @Test
    void shouldDoNothingWhenQueueIsEmpty() {

        when(taskQueueService.read(anyString()))
                .thenReturn(List.of());

        taskWorker.poll();

        verify(taskQueueService)
                .read(anyString());

        verifyNoInteractions(
                taskExecutionRepository,
                taskRunnerService,
                taskResultHandler
        );

        verify(taskQueueService, never())
                .acknowledge(any());
    }

    @Test
    void shouldReportFailureThroughTaskResultHandler() throws InterruptedException {

        StreamMessage streamMessage =
                createStreamMessage();

        TaskExecution taskExecution =
                createTaskExecution();

        when(taskExecutionRepository.findById(1010L))
                .thenReturn(
                        Optional.of(taskExecution)
                );

        when(taskRunnerService.executeWithRetry(
                1010L,
                1,
                30
        )).thenThrow(
                new RuntimeException("Task failed")
        );

        taskWorker.process(streamMessage);

        verify(taskResultHandler)
                .handle(any(TaskResult.class));

        verify(taskQueueService)
                .acknowledge(
                        streamMessage.recordId()
                );
    }
}