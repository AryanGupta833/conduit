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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuntimeWorkflowExecutorTest {

    @Mock
    private ExecutionRuntimeService executionRuntimeService;

    @Mock
    private TaskExecutionRepository taskExecutionRepository;

    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;

    @Mock
    private TaskDispatchService taskDispatchService;

    @InjectMocks
    private RuntimeWorkflowExecutor executor;

    private WorkflowExecution workflowExecution;
    private WorkflowVersion workflowVersion;

    @BeforeEach
    void setUp() {

        workflowVersion = new WorkflowVersion();
        workflowVersion.setId(1L);

        workflowExecution = new WorkflowExecution();
        workflowExecution.setId(100L);
        workflowExecution.setWorkflowVersion(workflowVersion);
        workflowExecution.setStatus(
                WorkflowExecutionStatus.RUNNING
        );
    }

    @Test
    void shouldQueueTaskSuccessfully() {

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);
        context.getScheduledTasks().add(10L);

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(executionRuntimeService.initializeContext(1L))
                .thenReturn(context);

        TaskExecution taskExecution =
                createTaskExecution(10L);

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        10L
                ))
                .thenReturn(Optional.of(taskExecution));

        doAnswer(invocation -> {

            TaskExecution task =
                    invocation.getArgument(0);

            task.setStatus(TaskExecutionStatus.QUEUED);

            return true;

        }).when(taskDispatchService)
                .dispatch(any(TaskExecution.class));

        executor.execute(100L);

        assertEquals(
                TaskExecutionStatus.QUEUED,
                taskExecution.getStatus()
        );

        verify(taskDispatchService)
                .dispatch(taskExecution);
    }

    @Test
    void shouldQueueMultipleReadyTasks() {

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);
        context.getReadyQueue().offer(20L);

        context.getScheduledTasks().add(10L);
        context.getScheduledTasks().add(20L);

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(executionRuntimeService.initializeContext(1L))
                .thenReturn(context);

        TaskExecution task10 =
                createTaskExecution(10L);

        TaskExecution task20 =
                createTaskExecution(20L);

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        10L
                ))
                .thenReturn(Optional.of(task10));

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        20L
                ))
                .thenReturn(Optional.of(task20));

        doAnswer(invocation -> {

            TaskExecution task =
                    invocation.getArgument(0);

            task.setStatus(TaskExecutionStatus.QUEUED);

            return true;

        }).when(taskDispatchService)
                .dispatch(any(TaskExecution.class));

        executor.execute(100L);

        assertEquals(
                TaskExecutionStatus.QUEUED,
                task10.getStatus()
        );

        assertEquals(
                TaskExecutionStatus.QUEUED,
                task20.getStatus()
        );

        verify(taskDispatchService)
                .dispatch(task10);

        verify(taskDispatchService)
                .dispatch(task20);
    }

    @Test
    void shouldSkipTaskWithoutDispatching() {

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);
        context.getScheduledTasks().add(10L);

        context.getTaskStatuses().put(
                10L,
                TaskExecutionStatus.SKIPPED
        );

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(executionRuntimeService.initializeContext(1L))
                .thenReturn(context);

        TaskExecution taskExecution =
                createTaskExecution(10L);

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        10L
                ))
                .thenReturn(Optional.of(taskExecution));

        executor.execute(100L);

        assertEquals(
                TaskExecutionStatus.SKIPPED,
                taskExecution.getStatus()
        );

        verify(taskExecutionRepository)
                .save(taskExecution);

        verify(taskDispatchService, never())
                .dispatch(any(TaskExecution.class));
    }

    @Test
    void shouldMarkRemainingTasksSkippedWhenWorkflowCancelled() {

        workflowExecution.setStatus(
                WorkflowExecutionStatus.CANCELLED
        );

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);
        context.getScheduledTasks().add(10L);

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(executionRuntimeService.initializeContext(1L))
                .thenReturn(context);

        TaskExecution taskExecution =
                createTaskExecution(10L);

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(List.of(taskExecution));

        executor.execute(100L);

        assertEquals(
                TaskExecutionStatus.SKIPPED,
                taskExecution.getStatus()
        );

        verify(taskExecutionRepository)
                .save(taskExecution);

        verify(taskDispatchService, never())
                .dispatch(any(TaskExecution.class));
    }

    @Test
    void shouldDoNothingWhenWorkflowPaused() {

        workflowExecution.setStatus(
                WorkflowExecutionStatus.PAUSED
        );

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(executionRuntimeService.initializeContext(1L))
                .thenReturn(context);

        executor.execute(100L);

        verify(taskExecutionRepository, never())
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        anyLong(),
                        anyLong()
                );

        verify(taskDispatchService, never())
                .dispatch(any(TaskExecution.class));
    }

    @Test
    void shouldNotQueueAnythingWhenReadyQueueIsEmpty() {

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(executionRuntimeService.initializeContext(1L))
                .thenReturn(context);

        executor.execute(100L);

        verify(taskDispatchService, never())
                .dispatch(any(TaskExecution.class));
    }

    private TaskExecution createTaskExecution(Long taskId) {

        TaskNode taskNode =
                new TaskNode();

        taskNode.setId(taskId);

        return TaskExecution.builder()
                .id(taskId + 1000)
                .taskNode(taskNode)
                .status(TaskExecutionStatus.PENDING)
                .retryCount(0)
                .idempotencyKey(
                        "workflow-100:task-" + taskId
                )
                .build();
    }
}