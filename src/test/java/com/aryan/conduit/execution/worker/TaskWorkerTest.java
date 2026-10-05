package com.aryan.conduit.execution.worker;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.queue.StreamMessage;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.queue.TaskResult;
import com.aryan.conduit.execution.queue.TaskResultHandler;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.retry.TaskAlreadyInProgressException;
import com.aryan.conduit.execution.retry.TaskLeaseLostException;
import com.aryan.conduit.execution.retry.TaskExecutionCancelledException;
import com.aryan.conduit.execution.service.TaskRunnerService;
import com.aryan.conduit.execution.service.HumanTaskService;
import com.aryan.conduit.observability.ConduitMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.aryan.conduit.workflow.entity.TaskNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                        taskResultHandler,
                        new ConduitMetrics(new SimpleMeterRegistry()),
                        mock(HumanTaskService.class)
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

        java.util.Map<String, Object> durableResult = java.util.Map.of(
                "success", true, "output", "stored", "variables", java.util.Map.of("customerId", "C123"));
        when(taskRunnerService.executeWithRetry(
                1010L,
                1,
                30
        )).thenReturn(durableResult);

        taskWorker.process(streamMessage);

        assertEquals(TaskExecutionStatus.QUEUED, taskExecution.getStatus());
        verify(taskExecutionRepository, never()).save(taskExecution);

        verify(taskRunnerService)
                .executeWithRetry(
                        1010L,
                        1,
                        30
                );

        verify(taskResultHandler)
                .handle(argThat(result -> result.success() && result.resultData().equals(durableResult)));

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
    void recoveredDeliveryIsSubmittedToBoundedWorkerExecutor() {
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        taskWorker.setTaskWorkerExecutor(executor);

        assertEquals(true, taskWorker.submit(createStreamMessage()));

        verify(executor).execute(any(Runnable.class));
        verifyNoInteractions(taskExecutionRepository, taskRunnerService, taskResultHandler);
    }

    @Test
    void saturatedWorkerPoolLeavesRecoveredDeliveryPending() {
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        taskWorker.setTaskWorkerExecutor(executor);
        doThrow(new java.util.concurrent.RejectedExecutionException("full"))
                .when(executor).execute(any(Runnable.class));

        assertEquals(false, taskWorker.submit(createStreamMessage()));

        verify(taskQueueService, never()).acknowledge(any());
        verifyNoInteractions(taskExecutionRepository, taskRunnerService, taskResultHandler);
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

    @Test
    void shouldLeaveDuplicateDeliveryPendingWhileAnotherWorkerOwnsLease() throws InterruptedException {
        StreamMessage streamMessage = createStreamMessage();
        TaskExecution taskExecution = createTaskExecution();
        taskExecution.setStatus(TaskExecutionStatus.RUNNING);

        when(taskExecutionRepository.findById(1010L)).thenReturn(Optional.of(taskExecution));
        when(taskRunnerService.executeWithRetry(1010L, 1, 30))
                .thenThrow(new TaskAlreadyInProgressException("100:10"));

        taskWorker.process(streamMessage);

        assertEquals(TaskExecutionStatus.RUNNING, taskExecution.getStatus());
        verify(taskResultHandler, never()).handle(any());
        verify(taskQueueService, never()).acknowledge(any());
    }

    @Test
    void staleWorkerLeaseLossMustNotOverwriteNewOwnerResultOrAcknowledge() throws InterruptedException {
        StreamMessage streamMessage = createStreamMessage();
        TaskExecution taskExecution = createTaskExecution();
        taskExecution.setStatus(TaskExecutionStatus.RUNNING);

        when(taskExecutionRepository.findById(1010L)).thenReturn(Optional.of(taskExecution));
        when(taskRunnerService.executeWithRetry(1010L, 1, 30))
                .thenThrow(new TaskLeaseLostException("lease reclaimed by another worker"));

        taskWorker.process(streamMessage);

        assertEquals(TaskExecutionStatus.RUNNING, taskExecution.getStatus());
        verify(taskResultHandler, never()).handle(any());
        verify(taskQueueService, never()).acknowledge(any());
    }

    @Test
    void cancelledBeforeStartTaskIsAcknowledgedWithoutFailurePropagation() throws InterruptedException {
        StreamMessage streamMessage = createStreamMessage();
        TaskExecution taskExecution = createTaskExecution();
        when(taskExecutionRepository.findById(1010L)).thenReturn(Optional.of(taskExecution));
        when(taskRunnerService.executeWithRetry(1010L, 1, 30))
                .thenThrow(new TaskExecutionCancelledException("workflow cancelled"));

        taskWorker.process(streamMessage);

        verify(taskResultHandler, never()).handle(any());
        verify(taskQueueService).acknowledge(streamMessage.recordId());
    }

    @Test
    void shouldAcknowledgeDuplicateDeliveryForTerminalTask() throws InterruptedException {
        StreamMessage streamMessage = createStreamMessage();
        TaskExecution taskExecution = createTaskExecution();
        taskExecution.setStatus(TaskExecutionStatus.SUCCESS);

        when(taskExecutionRepository.findById(1010L)).thenReturn(Optional.of(taskExecution));

        taskWorker.process(streamMessage);

        verify(taskRunnerService, never()).executeWithRetry(any(), any(), any());
        verify(taskResultHandler).reconcile(1010L);
        verify(taskQueueService).acknowledge(streamMessage.recordId());
    }

    @Test
    void resultCoordinationFailureLeavesSuccessfulDeliveryPending() throws InterruptedException {
        StreamMessage streamMessage = createStreamMessage();
        TaskExecution taskExecution = createTaskExecution();
        when(taskExecutionRepository.findById(1010L)).thenReturn(Optional.of(taskExecution));
        when(taskRunnerService.executeWithRetry(1010L, 1, 30)).thenReturn(new HashMap<>());
        doThrow(new IllegalStateException("database unavailable"))
                .when(taskResultHandler).handle(argThat(TaskResult::success));

        taskWorker.process(streamMessage);

        verify(taskResultHandler, times(1)).handle(argThat(TaskResult::success));
        verify(taskResultHandler, never()).handle(argThat(result -> !result.success()));
        verify(taskQueueService, never()).acknowledge(any());
    }

    @Test
    void shouldNotConvertDurableSuccessToFailureWhenAcknowledgementFails() throws InterruptedException {
        StreamMessage streamMessage = createStreamMessage();
        TaskExecution taskExecution = createTaskExecution();
        when(taskExecutionRepository.findById(1010L)).thenReturn(Optional.of(taskExecution));
        when(taskRunnerService.executeWithRetry(1010L, 1, 30)).thenReturn(new HashMap<>());
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(taskQueueService).acknowledge(streamMessage.recordId());

        assertThrows(IllegalStateException.class, () -> taskWorker.process(streamMessage));

        verify(taskResultHandler, times(1)).handle(argThat(TaskResult::success));
    }
}
