package com.aryan.conduit.trigger;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.service.ExecutionCreationService;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowTriggerInboxServiceTest {
    @Mock
    private WorkflowTriggerInboxRepository inboxRepository;
    @Mock
    private ExecutionCreationService executionCreationService;
    @InjectMocks
    private WorkflowTriggerInboxService service;

    @Test
    void createsAndAssociatesExecutionForNewEvent() {
        UUID eventId = UUID.randomUUID();
        WorkflowExecution execution = WorkflowExecution.builder().id(200L).build();
        when(inboxRepository.reserve(eventId, 10L)).thenReturn(1);
        when(executionCreationService.createExecution(10L))
                .thenReturn(new ExecutionContext(execution, List.of(), Map.of()));
        when(inboxRepository.attachExecution(eventId, 200L)).thenReturn(1);

        assertEquals(200L, service.getOrCreateExecution(eventId, 10L));
        verify(executionCreationService).createExecution(10L);
        verify(inboxRepository).attachExecution(eventId, 200L);
    }

    @Test
    void duplicateEventReusesExistingExecution() {
        UUID eventId = UUID.randomUUID();
        WorkflowTriggerInbox existing = new WorkflowTriggerInbox();
        existing.setEventId(eventId);
        existing.setWorkflowId(10L);
        existing.setWorkflowExecution(WorkflowExecution.builder().id(200L).build());
        when(inboxRepository.reserve(eventId, 10L)).thenReturn(0);
        when(inboxRepository.findById(eventId)).thenReturn(Optional.of(existing));

        assertEquals(200L, service.getOrCreateExecution(eventId, 10L));
        verify(executionCreationService, never()).createExecution(10L);
    }

    @Test
    void rejectsReusingEventIdForAnotherWorkflow() {
        UUID eventId = UUID.randomUUID();
        WorkflowTriggerInbox existing = new WorkflowTriggerInbox();
        existing.setEventId(eventId);
        existing.setWorkflowId(10L);
        existing.setWorkflowExecution(WorkflowExecution.builder().id(200L).build());
        when(inboxRepository.reserve(eventId, 11L)).thenReturn(0);
        when(inboxRepository.findById(eventId)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class,
                () -> service.getOrCreateExecution(eventId, 11L));
        verify(executionCreationService, never()).createExecution(11L);
    }
}
