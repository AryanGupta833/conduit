package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class TaskDispatchPostgresConcurrencyTest {

    @Autowired
    private TaskExecutionRepository taskExecutionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        taskExecutionRepository.deleteAll();
    }

    @Test
    void shouldAllowOnlyOneConcurrentWorkerToClaimTask()
            throws Exception {

        TaskExecution taskExecution =
                TaskExecution.builder()
                        .status(TaskExecutionStatus.PENDING)
                        .retryCount(0)
                        .idempotencyKey(
                                "postgres-concurrency-test"
                        )
                        .build();

        taskExecution =
                taskExecutionRepository.saveAndFlush(
                        taskExecution
                );

        Long taskExecutionId =
                taskExecution.getId();

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Integer> results =
                new ArrayList<>();

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            var future1 =
                    executor.submit(() ->
                            claimTask(
                                    taskExecutionId,
                                    ready,
                                    start
                            )
                    );

            var future2 =
                    executor.submit(() ->
                            claimTask(
                                    taskExecutionId,
                                    ready,
                                    start
                            )
                    );

            boolean workersReady =
                    ready.await(
                            10,
                            TimeUnit.SECONDS
                    );

            if (!workersReady) {
                throw new IllegalStateException(
                        "Workers did not become ready"
                );
            }

            start.countDown();

            results.add(
                    future1.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            results.add(
                    future2.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );

        } finally {
            executor.shutdownNow();
        }

        long successfulClaims =
                results.stream()
                        .filter(result -> result == 1)
                        .count();

        long rejectedClaims =
                results.stream()
                        .filter(result -> result == 0)
                        .count();

        assertEquals(
                1,
                successfulClaims,
                "Exactly one worker must claim the task"
        );

        assertEquals(
                1,
                rejectedClaims,
                "Exactly one worker must lose the race"
        );

        TaskExecution finalTask =
                taskExecutionRepository
                        .findById(taskExecutionId)
                        .orElseThrow();

        assertEquals(
                TaskExecutionStatus.QUEUED,
                finalTask.getStatus()
        );
    }

    private int claimTask(
            Long taskExecutionId,
            CountDownLatch ready,
            CountDownLatch start
    ) {

        ready.countDown();

        try {

            boolean started =
                    start.await(
                            10,
                            TimeUnit.SECONDS
                    );

            if (!started) {
                throw new IllegalStateException(
                        "Worker did not receive start signal"
                );
            }

            return transactionTemplate.execute(
                    transactionStatus ->
                            taskExecutionRepository
                                    .claimForQueue(
                                            taskExecutionId,
                                            TaskExecutionStatus.PENDING,
                                            TaskExecutionStatus.QUEUED
                                    )
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Worker interrupted",
                    e
            );
        }
    }
}