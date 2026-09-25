package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.dto.RuntimeExecutionContext;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.service.RuntimeWorkflowExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private TaskQueueService taskQueueService;

    @Mock
    private WorkflowExecution workflowExecution;

    @Mock
    private WorkflowVersion workflowVersion;

    private RuntimeWorkflowExecutor executor;

    @BeforeEach
    void setUp() {

        executor = new RuntimeWorkflowExecutor(
                executionRuntimeService,
                taskExecutionRepository,
                workflowExecutionRepository,
                taskQueueService
        );

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(workflowExecution.getWorkflowVersion())
                .thenReturn(workflowVersion);

        when(workflowVersion.getId())
                .thenReturn(10L);
    }

    private TaskExecution createTaskExecution(
            Long taskExecutionId,
            Long taskNodeId
    ) {

        TaskNode taskNode =
                TaskNode.builder()
                        .id(taskNodeId)
                        .name("Task-" + taskNodeId)
                        .maxRetries(1)
                        .timeoutSeconds(30)
                        .build();

        return TaskExecution.builder()
                .id(taskExecutionId)
                .workflowExecution(workflowExecution)
                .taskNode(taskNode)
                .status(TaskExecutionStatus.PENDING)
                .retryCount(0)
                .idempotencyKey("100:" + taskNodeId)
                .build();
    }

    @Test
    void shouldQueueTaskSuccessfully() {

        when(workflowExecution.getStatus())
                .thenReturn(WorkflowExecutionStatus.RUNNING);

        TaskExecution taskExecution =
                createTaskExecution(1010L, 10L);

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);

        when(executionRuntimeService.initializeContext(10L))
                .thenReturn(context);

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        10L
                ))
                .thenReturn(Optional.of(taskExecution));

        executor.execute(100L);

        assertEquals(
                TaskExecutionStatus.QUEUED,
                taskExecution.getStatus()
        );

        verify(taskExecutionRepository)
                .save(taskExecution);

        ArgumentCaptor<TaskMessage> captor =
                ArgumentCaptor.forClass(TaskMessage.class);

        verify(taskQueueService)
                .enqueue(captor.capture());

        TaskMessage message = captor.getValue();

        assertNotNull(message);

        assertEquals(
                100L,
                message.workflowExecutionId()
        );

        assertEquals(
                1010L,
                message.taskExecutionId()
        );

        assertEquals(
                10L,
                message.taskNodeId()
        );
    }

    @Test
    void shouldQueueMultipleReadyTasks() {

        when(workflowExecution.getStatus())
                .thenReturn(WorkflowExecutionStatus.RUNNING);

        TaskExecution task1 =
                createTaskExecution(1010L, 10L);

        TaskExecution task2 =
                createTaskExecution(1020L, 20L);

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);
        context.getReadyQueue().offer(20L);

        when(executionRuntimeService.initializeContext(10L))
                .thenReturn(context);

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        10L
                ))
                .thenReturn(Optional.of(task1));

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        20L
                ))
                .thenReturn(Optional.of(task2));

        executor.execute(100L);

        assertEquals(
                TaskExecutionStatus.QUEUED,
                task1.getStatus()
        );

        assertEquals(
                TaskExecutionStatus.QUEUED,
                task2.getStatus()
        );

        verify(taskQueueService, times(2))
                .enqueue(any(TaskMessage.class));

        verify(taskExecutionRepository, times(2))
                .save(any(TaskExecution.class));
    }

    @Test
    void shouldSkipTaskWithoutQueueing() {

        when(workflowExecution.getStatus())
                .thenReturn(WorkflowExecutionStatus.RUNNING);

        TaskExecution taskExecution =
                createTaskExecution(1010L, 10L);

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);

        context.getTaskStatuses().put(
                10L,
                TaskExecutionStatus.SKIPPED
        );

        when(executionRuntimeService.initializeContext(10L))
                .thenReturn(context);

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

        verify(taskQueueService, never())
                .enqueue(any(TaskMessage.class));
    }

    @Test
    void shouldNotQueueAnythingWhenReadyQueueIsEmpty() {

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        when(executionRuntimeService.initializeContext(10L))
                .thenReturn(context);

        executor.execute(100L);

        verify(taskQueueService, never())
                .enqueue(any(TaskMessage.class));

        verify(taskExecutionRepository, never())
                .save(any(TaskExecution.class));
    }

    @Test
    void shouldStopDispatchingWhenWorkflowIsPaused() {

        when(workflowExecution.getStatus())
                .thenReturn(WorkflowExecutionStatus.PAUSED);

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);

        when(executionRuntimeService.initializeContext(10L))
                .thenReturn(context);

        executor.execute(100L);

        verify(taskQueueService, never())
                .enqueue(any(TaskMessage.class));
    }

    @Test
    void shouldSkipRemainingTasksWhenWorkflowIsCancelled() {

        when(workflowExecution.getStatus())
                .thenReturn(WorkflowExecutionStatus.CANCELLED);

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getReadyQueue().offer(10L);

        TaskExecution taskExecution =
                createTaskExecution(1010L, 10L);

        when(executionRuntimeService.initializeContext(10L))
                .thenReturn(context);

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(List.of(taskExecution));

        executor.execute(100L);

        assertEquals(
                TaskExecutionStatus.SKIPPED,
                taskExecution.getStatus()
        );

        verify(taskQueueService, never())
                .enqueue(any(TaskMessage.class));

        verify(taskExecutionRepository)
                .save(taskExecution);
    }
}