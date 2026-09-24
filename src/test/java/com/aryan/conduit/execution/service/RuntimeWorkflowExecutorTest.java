package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.dto.RuntimeExecutionContext;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.service.RuntimeWorkflowExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuntimeWorkflowExecutorTest {

    @Mock
    private ExecutionRuntimeService executionRuntimeService;

    @Mock
    private TaskExecutionRepository taskExecutionRepository;

    @Mock
    private TaskRunnerService taskRunnerService;

    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;

    @InjectMocks
    private RuntimeWorkflowExecutor executor;

    private WorkflowExecution workflowExecution;
    private WorkflowVersion workflowVersion;

    @BeforeEach
    void setUp() {

        workflowVersion = mock(WorkflowVersion.class);

        when(workflowVersion.getId())
                .thenReturn(1L);

        workflowExecution = mock(WorkflowExecution.class);

        when(workflowExecution.getWorkflowVersion())
                .thenReturn(workflowVersion);

        when(workflowExecution.getStatus())
                .thenReturn(WorkflowExecutionStatus.RUNNING);

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));
    }

    @Test
    void shouldExecuteTaskSuccessfully() throws Exception {

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);
        context.getScheduledTasks().add(10L);

        when(executionRuntimeService.initializeContext(1L))
                .thenReturn(context);

        TaskExecution taskExecution =
                createTaskExecution(10L);

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(100L, 10L))
                .thenReturn(Optional.of(taskExecution));

        when(taskRunnerService.executeWithRetry(
                anyLong(),
                any(),
                any()
        )).thenReturn(null);

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(List.of(taskExecution));

        executor.execute(100L);

        verify(taskRunnerService)
                .executeWithRetry(
                        eq(taskExecution.getId()),
                        eq(1),
                        eq(30)
                );

        assertEquals(
                TaskExecutionStatus.SUCCESS,
                context.getTaskStatuses().get(10L)
        );

        assertEquals(
                TaskExecutionStatus.SUCCESS,
                taskExecution.getStatus()
        );

        verify(workflowExecutionRepository)
                .save(workflowExecution);
    }

    @Test
    void shouldSkipTaskWithoutExecutingRunner() throws Exception {

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);
        context.getScheduledTasks().add(10L);

        context.getTaskStatuses().put(
                10L,
                TaskExecutionStatus.SKIPPED
        );

        when(executionRuntimeService.initializeContext(1L))
                .thenReturn(context);

        TaskExecution taskExecution =
                createTaskExecution(10L);

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(100L, 10L))
                .thenReturn(Optional.of(taskExecution));

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(List.of(taskExecution));

        executor.execute(100L);

        verify(taskRunnerService, never())
                .executeWithRetry(
                        anyLong(),
                        any(),
                        any()
                );

        assertEquals(
                TaskExecutionStatus.SKIPPED,
                taskExecution.getStatus()
        );

        verify(taskExecutionRepository)
                .save(taskExecution);
    }

    @Test
    void shouldMarkWorkflowFailedWhenTaskFails() throws Exception {

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);
        context.getScheduledTasks().add(10L);

        when(executionRuntimeService.initializeContext(1L))
                .thenReturn(context);

        TaskExecution taskExecution =
                createTaskExecution(10L);

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(100L, 10L))
                .thenReturn(Optional.of(taskExecution));

        when(taskRunnerService.executeWithRetry(
                anyLong(),
                any(),
                any()
        )).thenThrow(
                new RuntimeException("Task failed")
        );

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(List.of(taskExecution));

        executor.execute(100L);

        assertEquals(
                TaskExecutionStatus.FAILED,
                context.getTaskStatuses().get(10L)
        );

        assertEquals(
                TaskExecutionStatus.FAILED,
                taskExecution.getStatus()
        );

        verify(workflowExecution)
                .setStatus(
                        WorkflowExecutionStatus.FAILED
                );
    }

    @Test
    void shouldMarkWorkflowSuccessWhenTaskSucceedsAndOthersSkipped()
            throws Exception {

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);
        context.getScheduledTasks().add(10L);

        when(executionRuntimeService.initializeContext(1L))
                .thenReturn(context);

        TaskExecution executedTask =
                createTaskExecution(10L);

        TaskExecution skippedTask =
                createTaskExecution(20L);

        context.getTaskStatuses().put(
                20L,
                TaskExecutionStatus.SKIPPED
        );

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(100L, 10L))
                .thenReturn(Optional.of(executedTask));

        when(taskRunnerService.executeWithRetry(
                anyLong(),
                any(),
                any()
        )).thenReturn(null);

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(
                        List.of(
                                executedTask,
                                skippedTask
                        )
                );

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        20L
                ))
                .thenReturn(Optional.of(skippedTask));

        executor.execute(100L);

        assertEquals(
                TaskExecutionStatus.SUCCESS,
                context.getTaskStatuses().get(10L)
        );

        assertEquals(
                TaskExecutionStatus.SUCCESS,
                executedTask.getStatus()
        );

        assertEquals(
                TaskExecutionStatus.SKIPPED,
                skippedTask.getStatus()
        );

        verify(workflowExecution)
                .setStatus(
                        WorkflowExecutionStatus.SUCCESS
                );
    }

    @Test
    void shouldEvaluateChildrenAfterTaskExecution()
            throws Exception {

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);
        context.getScheduledTasks().add(10L);

        when(executionRuntimeService.initializeContext(1L))
                .thenReturn(context);

        TaskExecution taskExecution =
                createTaskExecution(10L);

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(100L, 10L))
                .thenReturn(Optional.of(taskExecution));

        when(taskRunnerService.executeWithRetry(
                anyLong(),
                any(),
                any()
        )).thenReturn(null);

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(List.of(taskExecution));

        executor.execute(100L);

        verify(executionRuntimeService)
                .evaluateChildren(
                        eq(100L),
                        eq(10L),
                        eq(context)
                );
    }

    private TaskExecution createTaskExecution(Long taskId) {

        TaskNode taskNode = TaskNode.builder()
                .id(taskId)
                .name("Task-" + taskId)
                .maxRetries(1)
                .timeoutSeconds(30)
                .build();

        return TaskExecution.builder()
                .id(taskId + 1000)
                .workflowExecution(workflowExecution)
                .taskNode(taskNode)
                .status(TaskExecutionStatus.PENDING)
                .retryCount(0)
                .idempotencyKey("test-" + taskId)
                .build();
    }
}