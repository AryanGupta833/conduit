package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.DependencyCondition;
import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistributedWorkflowCoordinatorTest {

    @Mock
    private TaskExecutionRepository taskExecutionRepository;

    @Mock
    private WorkflowExecutionRepository workflowExecutionRepository;

    @Mock
    private DependencyRepository dependencyRepository;

    @Mock
    private ExecutionRuntimeService executionRuntimeService;

    @Mock
    private TaskDispatchService taskDispatchService;

    @InjectMocks
    private DistributedWorkflowCoordinator coordinator;

    private WorkflowExecution workflowExecution;
    private WorkflowVersion workflowVersion;

    private TaskNode taskA;
    private TaskNode taskB;

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

        taskA = new TaskNode();
        taskA.setId(1L);
        taskA.setName("A");

        taskB = new TaskNode();
        taskB.setId(2L);
        taskB.setName("B");
    }

    @Test
    void shouldQueueChildWhenParentSucceeds() {

        TaskExecution executionA =
                createTaskExecution(
                        101L,
                        taskA,
                        TaskExecutionStatus.SUCCESS
                );

        TaskExecution executionB =
                createTaskExecution(
                        102L,
                        taskB,
                        TaskExecutionStatus.PENDING
                );

        Dependency dependency =
                Dependency.builder()
                        .parent(taskA)
                        .child(taskB)
                        .condition(
                                DependencyCondition.ON_SUCCESS
                        )
                        .build();

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(
                        List.of(
                                executionA,
                                executionB
                        )
                );

        when(dependencyRepository
                .findByParent_Id(1L))
                .thenReturn(
                        List.of(dependency)
                );

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        2L
                ))
                .thenReturn(
                        Optional.of(executionB)
                );

        when(executionRuntimeService.canRun(
                eq(100L),
                eq(2L),
                anyMap()
        )).thenReturn(true);

        when(taskDispatchService.dispatch(executionB))
                .thenReturn(true);

        coordinator.handleTaskCompletion(
                100L,
                1L
        );

        assertEquals(
                TaskExecutionStatus.QUEUED,
                executionB.getStatus()
        );

        verify(taskDispatchService)
                .dispatch(executionB);
    }

    private TaskExecution createTaskExecution(
            Long executionId,
            TaskNode taskNode,
            TaskExecutionStatus status
    ) {

        return TaskExecution.builder()
                .id(executionId)
                .taskNode(taskNode)
                .status(status)
                .retryCount(0)
                .idempotencyKey(
                        "workflow-100:task-"
                                + taskNode.getId()
                )
                .build();
    }
    @Test
    void shouldQueueChildWhenParentFails() {

        TaskExecution executionA =
                createTaskExecution(
                        101L,
                        taskA,
                        TaskExecutionStatus.FAILED
                );

        TaskExecution executionB =
                createTaskExecution(
                        102L,
                        taskB,
                        TaskExecutionStatus.PENDING
                );

        Dependency dependency =
                Dependency.builder()
                        .parent(taskA)
                        .child(taskB)
                        .condition(
                                DependencyCondition.ON_FAILURE
                        )
                        .build();

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(
                        List.of(
                                executionA,
                                executionB
                        )
                );

        when(dependencyRepository
                .findByParent_Id(1L))
                .thenReturn(
                        List.of(dependency)
                );

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        2L
                ))
                .thenReturn(
                        Optional.of(executionB)
                );

        when(executionRuntimeService.canRun(
                eq(100L),
                eq(2L),
                anyMap()
        )).thenReturn(true);

        when(taskDispatchService.dispatch(executionB))
                .thenReturn(true);

        coordinator.handleTaskCompletion(
                100L,
                1L
        );

        assertEquals(
                TaskExecutionStatus.QUEUED,
                executionB.getStatus()
        );

        verify(taskDispatchService)
                .dispatch(executionB);
    }
    @Test
    void shouldSkipChildWhenParentFailsAndConditionIsOnSuccess() {

        TaskExecution executionA =
                createTaskExecution(
                        101L,
                        taskA,
                        TaskExecutionStatus.FAILED
                );

        TaskExecution executionB =
                createTaskExecution(
                        102L,
                        taskB,
                        TaskExecutionStatus.PENDING
                );

        Dependency dependency =
                Dependency.builder()
                        .parent(taskA)
                        .child(taskB)
                        .condition(
                                DependencyCondition.ON_SUCCESS
                        )
                        .build();

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(
                        List.of(
                                executionA,
                                executionB
                        )
                );

        when(dependencyRepository
                .findByParent_Id(1L))
                .thenReturn(
                        List.of(dependency)
                );

        when(dependencyRepository
                .findByChild_Id(2L))
                .thenReturn(
                        List.of(dependency)
                );

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        2L
                ))
                .thenReturn(
                        Optional.of(executionB)
                );

        when(executionRuntimeService.canRun(
                eq(100L),
                eq(2L),
                anyMap()
        )).thenReturn(false);

        coordinator.handleTaskCompletion(
                100L,
                1L
        );

        assertEquals(
                TaskExecutionStatus.SKIPPED,
                executionB.getStatus()
        );

        verify(taskExecutionRepository)
                .save(executionB);

        verify(taskDispatchService, never())
                .dispatch(any(TaskExecution.class));
    }
    @Test
    void shouldNotQueueChildUntilAllParentsSucceed() {

        TaskNode taskC = new TaskNode();
        taskC.setId(3L);
        taskC.setName("C");

        TaskExecution executionA =
                createTaskExecution(
                        101L,
                        taskA,
                        TaskExecutionStatus.SUCCESS
                );

        TaskExecution executionB =
                createTaskExecution(
                        102L,
                        taskB,
                        TaskExecutionStatus.PENDING
                );

        TaskExecution executionC =
                createTaskExecution(
                        103L,
                        taskC,
                        TaskExecutionStatus.PENDING
                );

        Dependency dependencyAC =
                Dependency.builder()
                        .parent(taskA)
                        .child(taskC)
                        .condition(
                                DependencyCondition.ON_SUCCESS
                        )
                        .build();

        Dependency dependencyBC =
                Dependency.builder()
                        .parent(taskB)
                        .child(taskC)
                        .condition(
                                DependencyCondition.ON_SUCCESS
                        )
                        .build();

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(
                        List.of(
                                executionA,
                                executionB,
                                executionC
                        )
                );

        when(dependencyRepository
                .findByParent_Id(1L))
                .thenReturn(
                        List.of(dependencyAC)
                );

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        3L
                ))
                .thenReturn(
                        Optional.of(executionC)
                );

        when(executionRuntimeService.canRun(
                eq(100L),
                eq(3L),
                anyMap()
        )).thenReturn(false);

        when(dependencyRepository
                .findByChild_Id(3L))
                .thenReturn(
                        List.of(
                                dependencyAC,
                                dependencyBC
                        )
                );

        coordinator.handleTaskCompletion(
                100L,
                1L
        );

        assertEquals(
                TaskExecutionStatus.PENDING,
                executionC.getStatus()
        );

        verify(taskDispatchService, never())
                .dispatch(any(TaskExecution.class));
    }
    @Test
    void shouldQueueChildWhenAllParentsSucceed() {

        TaskNode taskC = new TaskNode();
        taskC.setId(3L);
        taskC.setName("C");

        TaskExecution executionA =
                createTaskExecution(
                        101L,
                        taskA,
                        TaskExecutionStatus.SUCCESS
                );

        TaskExecution executionB =
                createTaskExecution(
                        102L,
                        taskB,
                        TaskExecutionStatus.SUCCESS
                );

        TaskExecution executionC =
                createTaskExecution(
                        103L,
                        taskC,
                        TaskExecutionStatus.PENDING
                );

        Dependency dependencyAC =
                Dependency.builder()
                        .parent(taskA)
                        .child(taskC)
                        .condition(
                                DependencyCondition.ON_SUCCESS
                        )
                        .build();

        Dependency dependencyBC =
                Dependency.builder()
                        .parent(taskB)
                        .child(taskC)
                        .condition(
                                DependencyCondition.ON_SUCCESS
                        )
                        .build();

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(
                        List.of(
                                executionA,
                                executionB,
                                executionC
                        )
                );

        when(dependencyRepository
                .findByParent_Id(2L))
                .thenReturn(
                        List.of(dependencyBC)
                );

        when(taskExecutionRepository
                .findByWorkflowExecution_IdAndTaskNode_Id(
                        100L,
                        3L
                ))
                .thenReturn(
                        Optional.of(executionC)
                );

        when(executionRuntimeService.canRun(
                eq(100L),
                eq(3L),
                anyMap()
        )).thenReturn(true);

        when(taskDispatchService.dispatch(executionC))
                .thenReturn(true);

        coordinator.handleTaskCompletion(
                100L,
                2L
        );

        assertEquals(
                TaskExecutionStatus.QUEUED,
                executionC.getStatus()
        );

        verify(taskDispatchService)
                .dispatch(executionC);
    }

    @Test
    void shouldMarkWorkflowSuccessfulWhenAllTasksAreTerminal() {

        TaskExecution executionA =
                createTaskExecution(
                        101L,
                        taskA,
                        TaskExecutionStatus.SUCCESS
                );

        TaskExecution executionB =
                createTaskExecution(
                        102L,
                        taskB,
                        TaskExecutionStatus.SUCCESS
                );

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(
                        List.of(
                                executionA,
                                executionB
                        )
                );

        when(workflowExecutionRepository
                .findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        coordinator.handleTaskCompletion(
                100L,
                2L
        );

        assertEquals(
                WorkflowExecutionStatus.SUCCESS,
                workflowExecution.getStatus()
        );

        verify(workflowExecutionRepository)
                .save(workflowExecution);
    }
    @Test
    void shouldMarkWorkflowFailedWhenTaskFails() {

        TaskExecution executionA =
                createTaskExecution(
                        101L,
                        taskA,
                        TaskExecutionStatus.SUCCESS
                );

        TaskExecution executionB =
                createTaskExecution(
                        102L,
                        taskB,
                        TaskExecutionStatus.FAILED
                );

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(
                        List.of(
                                executionA,
                                executionB
                        )
                );

        coordinator.handleTaskCompletion(
                100L,
                2L
        );

        assertEquals(
                WorkflowExecutionStatus.FAILED,
                workflowExecution.getStatus()
        );

        verify(workflowExecutionRepository)
                .save(workflowExecution);
    }
    @Test
    void shouldNotCompleteWorkflowWhileTasksArePending() {

        TaskExecution executionA =
                createTaskExecution(
                        101L,
                        taskA,
                        TaskExecutionStatus.SUCCESS
                );

        TaskExecution executionB =
                createTaskExecution(
                        102L,
                        taskB,
                        TaskExecutionStatus.PENDING
                );

        when(workflowExecutionRepository.findById(100L))
                .thenReturn(Optional.of(workflowExecution));

        when(taskExecutionRepository
                .findByWorkflowExecution_Id(100L))
                .thenReturn(
                        List.of(
                                executionA,
                                executionB
                        )
                );

        coordinator.handleTaskCompletion(
                100L,
                1L
        );

        assertEquals(
                WorkflowExecutionStatus.RUNNING,
                workflowExecution.getStatus()
        );

        verify(workflowExecutionRepository, never())
                .save(workflowExecution);
    }


}