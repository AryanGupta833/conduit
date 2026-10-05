package com.aryan.conduit.execution.queue;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.service.DistributedWorkflowCoordinator;
import com.aryan.conduit.execution.service.ExecutionContextService;
import com.aryan.conduit.workflow.entity.TaskNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskResultHandlerTest {

    @Mock TaskExecutionRepository taskExecutionRepository;
    @Mock DistributedWorkflowCoordinator coordinator;
    @Mock ExecutionContextService contextService;

    @Test
    void successfulResultPersistsStateBeforeCoordinatorProgression() {
        TaskExecution task = task(TaskExecutionStatus.RUNNING);
        when(taskExecutionRepository.findById(101L)).thenReturn(Optional.of(task));

        new TaskResultHandler(taskExecutionRepository, coordinator, contextService).handle(
                new TaskResult(999L, 101L, 999L, true, false, null,
                        java.util.Map.of("output", "ok", "variables", java.util.Map.of("id", 7))));

        InOrder order = inOrder(taskExecutionRepository, contextService, coordinator);
        order.verify(taskExecutionRepository).save(task);
        order.verify(contextService).commitTaskResult(100L, 10L, null, "SUCCESS",
                java.util.Map.of("output", "ok", "variables", java.util.Map.of("id", 7)));
        order.verify(coordinator).handleTaskCompletion(100L, 10L);
    }

    @Test
    void staleFailureCannotOverwriteDurableSuccess() {
        TaskExecution task = task(TaskExecutionStatus.SUCCESS);
        when(taskExecutionRepository.findById(101L)).thenReturn(Optional.of(task));

        new TaskResultHandler(taskExecutionRepository, coordinator, contextService).handle(
                new TaskResult(100L, 101L, 10L, false, false, "stale failure"));

        verify(taskExecutionRepository, never()).save(any());
        verifyNoInteractions(contextService);
        verifyNoInteractions(coordinator);
    }

    @Test
    void terminalRedeliveryReplaysCoordinatorProgression() {
        TaskExecution task = task(TaskExecutionStatus.FAILED);
        when(taskExecutionRepository.findById(101L)).thenReturn(Optional.of(task));

        new TaskResultHandler(taskExecutionRepository, coordinator, contextService).reconcile(101L);

        verify(coordinator).handleTaskCompletion(100L, 10L);
    }

    private TaskExecution task(TaskExecutionStatus status) {
        return TaskExecution.builder()
                .id(101L)
                .workflowExecution(WorkflowExecution.builder().id(100L).build())
                .taskNode(TaskNode.builder().id(10L).build())
                .status(status)
                .build();
    }
}
