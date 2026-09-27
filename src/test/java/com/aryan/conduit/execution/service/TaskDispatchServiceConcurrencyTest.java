package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.workflow.entity.TaskNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskDispatchServiceConcurrencyTest {

    @Mock
    private TaskExecutionRepository taskExecutionRepository;

    @Mock
    private OutboxService outboxService;

    @Test
    void shouldAllowOnlyOneWorkerToClaimTask()
            throws Exception {

        TaskExecution taskExecution =
                mock(TaskExecution.class);

        WorkflowExecution workflowExecution =
                mock(WorkflowExecution.class);

        TaskNode taskNode =
                mock(TaskNode.class);

        when(taskExecution.getId())
                .thenReturn(100L);

        when(taskExecution.getWorkflowExecution())
                .thenReturn(workflowExecution);

        when(taskExecution.getTaskNode())
                .thenReturn(taskNode);

        when(workflowExecution.getId())
                .thenReturn(200L);

        when(taskNode.getId())
                .thenReturn(300L);

        /*
         * Both workers reach the claim operation
         * at approximately the same time.
         */
        CountDownLatch claimLatch =
                new CountDownLatch(2);

        AtomicInteger successfulClaims =
                new AtomicInteger(0);

        when(taskExecutionRepository.claimForQueue(
                eq(100L),
                eq(TaskExecutionStatus.PENDING),
                eq(TaskExecutionStatus.QUEUED)
        )).thenAnswer(invocation -> {

            claimLatch.countDown();

            boolean reached =
                    claimLatch.await(
                            5,
                            TimeUnit.SECONDS
                    );

            if (!reached) {
                throw new IllegalStateException(
                        "Concurrency test timed out"
                );
            }

            /*
             * Simulate the database's atomic
             *
             * UPDATE ... WHERE status = PENDING
             *
             * Only one worker gets the row.
             */
            return successfulClaims
                    .getAndIncrement() == 0
                    ? 1
                    : 0;
        });

        TaskDispatchService dispatchService =
                new TaskDispatchService(
                        taskExecutionRepository,
                        outboxService
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            var future1 =
                    executor.submit(() ->
                            dispatchService.dispatch(
                                    taskExecution
                            )
                    );

            var future2 =
                    executor.submit(() ->
                            dispatchService.dispatch(
                                    taskExecution
                            )
                    );

            boolean result1 =
                    future1.get(
                            10,
                            TimeUnit.SECONDS
                    );

            boolean result2 =
                    future2.get(
                            10,
                            TimeUnit.SECONDS
                    );

            /*
             * Exactly one worker must successfully
             * claim the task.
             */
            assertTrue(
                    result1 ^ result2,
                    "Exactly one worker should claim the task"
            );

        } finally {
            executor.shutdownNow();
        }

        /*
         * Both workers attempted the atomic claim.
         */
        verify(taskExecutionRepository, times(2))
                .claimForQueue(
                        eq(100L),
                        eq(TaskExecutionStatus.PENDING),
                        eq(TaskExecutionStatus.QUEUED)
                );

        /*
         * Only the successful claimant creates
         * an outbox event.
         */
        verify(outboxService, times(1))
                .createTaskQueueEvent(
                        any()
                );
    }
}