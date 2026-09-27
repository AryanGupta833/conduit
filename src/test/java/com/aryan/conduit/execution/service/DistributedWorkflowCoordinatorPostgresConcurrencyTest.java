package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
class DistributedWorkflowCoordinatorPostgresConcurrencyTest {

    @Autowired
    private TaskExecutionRepository taskExecutionRepository;

    @Autowired
    private WorkflowExecutionRepository workflowExecutionRepository;

    @Autowired
    private DistributedWorkflowCoordinator coordinator;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private DependencyRepository dependencyRepository;

    @MockitoBean
    private ExecutionRuntimeService executionRuntimeService;

    @BeforeEach
    void setUp() {
        taskExecutionRepository.deleteAll();
        workflowExecutionRepository.deleteAll();
    }

    @Test
    void shouldQueueChildOnlyOnceWhenTwoCompletionEventsRace()
            throws Exception {

        TestData testData = createTestData();

        final Long workflowExecutionId =
                testData.workflowExecutionId();

        final Long parentTaskId1 =
                testData.parentTaskId1();

        final Long parentTaskId2 =
                testData.parentTaskId2();

        final Long childTaskId =
                testData.childTaskId();

        Dependency dependency1 =
                Dependency.builder()
                        .parent(testData.parentNode1())
                        .child(testData.childNode())
                        .build();

        Dependency dependency2 =
                Dependency.builder()
                        .parent(testData.parentNode2())
                        .child(testData.childNode())
                        .build();

        when(dependencyRepository.findByParent_Id(parentTaskId1))
                .thenReturn(List.of(dependency1));

        when(dependencyRepository.findByParent_Id(parentTaskId2))
                .thenReturn(List.of(dependency2));

        when(dependencyRepository.findByChild_Id(childTaskId))
                .thenReturn(List.of(
                        dependency1,
                        dependency2
                ));

        when(executionRuntimeService.canRun(
                eq(workflowExecutionId),
                eq(childTaskId),
                any()
        )).thenReturn(true);

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            var future1 =
                    executor.submit(() ->
                            runCompletion(
                                    workflowExecutionId,
                                    parentTaskId1,
                                    ready,
                                    start
                            )
                    );

            var future2 =
                    executor.submit(() ->
                            runCompletion(
                                    workflowExecutionId,
                                    parentTaskId2,
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
                        "Coordinator workers did not become ready"
                );
            }

            start.countDown();

            future1.get(
                    15,
                    TimeUnit.SECONDS
            );

            future2.get(
                    15,
                    TimeUnit.SECONDS
            );

        } finally {
            executor.shutdownNow();
        }

        TaskExecution finalChild =
                taskExecutionRepository
                        .findById(testData.childExecutionId())
                        .orElseThrow();

        assertEquals(
                TaskExecutionStatus.QUEUED,
                finalChild.getStatus()
        );
    }

    private void runCompletion(
            Long workflowExecutionId,
            Long parentTaskId,
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
                        "Coordinator did not receive start signal"
                );
            }

            coordinator.handleTaskCompletion(
                    workflowExecutionId,
                    parentTaskId
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Coordinator interrupted",
                    e
            );
        }
    }

    private TestData createTestData() {

        return transactionTemplate.execute(status -> {

            WorkflowExecution workflowExecution =
                    WorkflowExecution.builder()
                            .status(WorkflowExecutionStatus.RUNNING)
                            .build();

            entityManager.persist(workflowExecution);

            TaskNode parentNode1 =
                    TaskNode.builder()
                            .name("parent-1")
                            .build();

            TaskNode parentNode2 =
                    TaskNode.builder()
                            .name("parent-2")
                            .build();

            TaskNode childNode =
                    TaskNode.builder()
                            .name("child")
                            .build();

            entityManager.persist(parentNode1);
            entityManager.persist(parentNode2);
            entityManager.persist(childNode);

            entityManager.flush();

            TaskExecution parentExecution1 =
                    TaskExecution.builder()
                            .workflowExecution(workflowExecution)
                            .taskNode(parentNode1)
                            .status(TaskExecutionStatus.SUCCESS)
                            .retryCount(0)
                            .idempotencyKey(
                                    "postgres-coordinator-parent-1"
                            )
                            .build();

            TaskExecution parentExecution2 =
                    TaskExecution.builder()
                            .workflowExecution(workflowExecution)
                            .taskNode(parentNode2)
                            .status(TaskExecutionStatus.SUCCESS)
                            .retryCount(0)
                            .idempotencyKey(
                                    "postgres-coordinator-parent-2"
                            )
                            .build();

            TaskExecution childExecution =
                    TaskExecution.builder()
                            .workflowExecution(workflowExecution)
                            .taskNode(childNode)
                            .status(TaskExecutionStatus.PENDING)
                            .retryCount(0)
                            .idempotencyKey(
                                    "postgres-coordinator-child"
                            )
                            .build();

            entityManager.persist(parentExecution1);
            entityManager.persist(parentExecution2);
            entityManager.persist(childExecution);

            entityManager.flush();

            return new TestData(
                    workflowExecution.getId(),
                    parentNode1.getId(),
                    parentNode2.getId(),
                    childNode.getId(),
                    childExecution.getId(),
                    parentNode1,
                    parentNode2,
                    childNode
            );
        });
    }

    private record TestData(
            Long workflowExecutionId,
            Long parentTaskId1,
            Long parentTaskId2,
            Long childTaskId,
            Long childExecutionId,
            TaskNode parentNode1,
            TaskNode parentNode2,
            TaskNode childNode
    ) {
    }
}