package com.aryan.conduit.trigger;

import com.aryan.conduit.workflow.service.WorkflowExecutionAsyncService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class WorkflowTriggerServiceTest {

    @Test
    void shouldCreateExecutionAndStartAsyncExecution() {

        WorkflowTriggerInboxService inboxService =
                mock(WorkflowTriggerInboxService.class);

        WorkflowExecutionAsyncService asyncService =
                mock(WorkflowExecutionAsyncService.class);

        WorkflowTriggerService triggerService =
                new WorkflowTriggerService(
                        inboxService,
                        asyncService
                );

        WorkflowTriggerEvent event = new WorkflowTriggerEvent(10L);
        when(inboxService.getOrCreateExecution(event.eventId(), 10L)).thenReturn(100L);

        Long executionId =
                triggerService.triggerWorkflow(event);

        assertEquals(100L, executionId);

        verify(inboxService).getOrCreateExecution(event.eventId(), 10L);

        verify(
                asyncService
        ).executeAsync(100L);
    }
}
