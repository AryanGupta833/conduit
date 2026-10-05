package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.service.ExecutionCreationService;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowScheduleClaimServiceTest {
    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final ExecutionCreationService executions = mock(ExecutionCreationService.class);
    private final WorkflowScheduleClaimService service = new WorkflowScheduleClaimService(workflows, executions);

    @Test
    void createsOneExecutionAndAdvancesCursorForDueOccurrence() {
        Workflow workflow = Workflow.builder().id(7L).active(true)
                .cronExpression("0 * * * * *")
                .createdAt(LocalDateTime.parse("2026-10-03T12:00:00"))
                .build();
        when(workflows.findByIdForUpdate(7L)).thenReturn(Optional.of(workflow));
        WorkflowExecution execution = WorkflowExecution.builder().id(99L).build();
        when(executions.createExecution(7L)).thenReturn(new ExecutionContext(execution, List.of(), Map.of()));

        Long id = service.createExecutionIfDue(7L, LocalDateTime.parse("2026-10-03T12:01:01"));

        assertEquals(99L, id);
        assertEquals(LocalDateTime.parse("2026-10-03T12:01:00"), workflow.getLastScheduledRun());
        verify(executions).createExecution(7L);
        verify(workflows).save(workflow);
    }

    @Test
    void doesNotCreateExecutionBeforeNextOccurrence() {
        Workflow workflow = Workflow.builder().id(7L).active(true)
                .cronExpression("0 * * * * *")
                .createdAt(LocalDateTime.parse("2026-10-03T12:00:00"))
                .build();
        when(workflows.findByIdForUpdate(7L)).thenReturn(Optional.of(workflow));

        assertNull(service.createExecutionIfDue(7L, LocalDateTime.parse("2026-10-03T12:00:59")));

        verifyNoInteractions(executions);
        verify(workflows, never()).save(any());
    }
}
