package com.aryan.conduit.trigger;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.service.ExecutionCreationService;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import com.aryan.conduit.workflow.service.WorkflowExecutionAsyncService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class WorkflowTriggerServiceTest {

    @Test
    void shouldCreateExecutionAndStartAsyncExecution() {

        ExecutionCreationService executionCreationService =
                mock(ExecutionCreationService.class);

        WorkflowExecutionAsyncService asyncService =
                mock(WorkflowExecutionAsyncService.class);

        WorkflowTriggerService triggerService =
                new WorkflowTriggerService(
                        executionCreationService,
                        asyncService
                );

        WorkflowExecution workflowExecution =
                WorkflowExecution.builder()
                        .id(100L)
                        .build();

        ExecutionContext context =
                new ExecutionContext(
                        workflowExecution,
                        java.util.List.of(),
                        java.util.Map.of()
                );

        when(
                executionCreationService.createExecution(10L)
        ).thenReturn(context);

        Long executionId =
                triggerService.triggerWorkflow(10L);

        assertEquals(100L, executionId);

        verify(
                executionCreationService
        ).createExecution(10L);

        verify(
                asyncService
        ).executeAsync(100L);
    }
}