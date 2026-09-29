package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aryan.conduit.execution.entity.WorkflowExecutionStatus.RUNNING;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRecoveryServiceTest {

    @Test
    void resumesDispatcherForInFlightWorkflowsWithoutMarkingThemFailed() {
        WorkflowExecutionRepository repository = mock(WorkflowExecutionRepository.class);
        WorkflowExecutionAsyncService asyncService = mock(WorkflowExecutionAsyncService.class);
        WorkflowExecution first = WorkflowExecution.builder().id(12L).status(RUNNING).build();
        WorkflowExecution second = WorkflowExecution.builder().id(34L).status(RUNNING).build();
        when(repository.findByStatus(RUNNING)).thenReturn(List.of(first, second));

        new WorkflowRecoveryService(repository, asyncService).resumeRunningExecutions();

        verify(asyncService).executeAsync(12L);
        verify(asyncService).executeAsync(34L);
    }
}
