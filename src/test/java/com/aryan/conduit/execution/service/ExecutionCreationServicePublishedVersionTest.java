package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.ExecutionContextEntity;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.ExecutionContextRepository;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.observability.ConduitMetrics;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.aryan.conduit.workflow.service.WorkflowGraphService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionCreationServicePublishedVersionTest {
    @Test
    void workflowExecutionUsesPublishedVersionInsteadOfNewestUnpublishedDraft() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowExecutionRepository executionRepository = mock(WorkflowExecutionRepository.class);
        TaskExecutionRepository taskExecutionRepository = mock(TaskExecutionRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        WorkflowGraphService graphService = mock(WorkflowGraphService.class);
        ExecutionContextRepository contextRepository = mock(ExecutionContextRepository.class);
        WorkflowVersionRepository versionRepository = mock(WorkflowVersionRepository.class);
        ConduitMetrics metrics = mock(ConduitMetrics.class);

        WorkflowVersion published = WorkflowVersion.builder()
                .id(22L).versionNumber(2).published(true).latest(false).build();
        when(versionRepository.findTopByWorkflow_IdAndPublishedTrueOrderByVersionNumberDesc(7L))
                .thenReturn(Optional.of(published));
        when(versionRepository.findById(22L)).thenReturn(Optional.of(published));
        when(graphService.generateExecutionStages(22L)).thenReturn(List.of(List.of(31L)));
        when(taskNodeRepository.findById(31L)).thenReturn(Optional.of(TaskNode.builder().id(31L).build()));
        when(executionRepository.save(any(WorkflowExecution.class))).thenAnswer(invocation -> {
            WorkflowExecution execution = invocation.getArgument(0);
            execution.setId(900L);
            return execution;
        });
        when(contextRepository.save(any(ExecutionContextEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(taskExecutionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ExecutionCreationService service = new ExecutionCreationService(
                workflowRepository, executionRepository, taskExecutionRepository,
                taskNodeRepository, graphService, contextRepository, versionRepository, metrics);

        var context = service.createExecution(7L);

        assertEquals(22L, context.workflowExecution().getWorkflowVersion().getId());
        assertEquals(WorkflowExecutionStatus.RUNNING, context.workflowExecution().getStatus());
        verify(versionRepository).findTopByWorkflow_IdAndPublishedTrueOrderByVersionNumberDesc(7L);
    }

    @Test
    void explicitExecutionCannotRunAnUnpublishedVersion() {
        WorkflowVersionRepository versionRepository = mock(WorkflowVersionRepository.class);
        WorkflowVersion draft = WorkflowVersion.builder()
                .id(23L).versionNumber(3).published(false).latest(true).build();
        when(versionRepository.findById(23L)).thenReturn(Optional.of(draft));
        ExecutionCreationService service = new ExecutionCreationService(
                mock(WorkflowRepository.class), mock(WorkflowExecutionRepository.class),
                mock(TaskExecutionRepository.class), mock(TaskNodeRepository.class),
                mock(WorkflowGraphService.class), mock(ExecutionContextRepository.class),
                versionRepository, mock(ConduitMetrics.class));

        assertThrows(IllegalStateException.class, () -> service.createExecutionForVersion(23L));
    }
}
