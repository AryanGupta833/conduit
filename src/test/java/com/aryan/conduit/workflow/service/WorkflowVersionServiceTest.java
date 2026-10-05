package com.aryan.conduit.workflow.service;

import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

class WorkflowVersionServiceTest {
    @Test
    void createVersionCopiesPluginConfigurationAndEditorFields() {
        WorkflowVersionRepository versionRepository = mock(WorkflowVersionRepository.class);
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowGraphService workflowGraphService = mock(WorkflowGraphService.class);
        TaskNodeRepository taskRepository = mock(TaskNodeRepository.class);
        DependencyRepository dependencyRepository = mock(DependencyRepository.class);
        Workflow workflow = Workflow.builder().id(7L).build();
        WorkflowVersion sourceVersion = WorkflowVersion.builder()
                .id(11L).workflow(workflow).versionNumber(1).latest(true).published(true).build();
        TaskNode sourceTask = TaskNode.builder()
                .id(21L).workflowVersion(sourceVersion).name("test")
                .displayName("Readable test").pluginType("SHELL")
                .configurationJson("{\"command\":\"echo ok\"}")
                .timeoutSeconds(45).maxRetries(2).xPosition(12.0).yPosition(34.0)
                .build();
        when(versionRepository.findTopByWorkflow_IdOrderByVersionNumberDesc(7L))
                .thenReturn(Optional.of(sourceVersion));
        when(workflowRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(workflow));
        when(versionRepository.save(any(WorkflowVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.findByWorkflowVersion_Id(11L)).thenReturn(List.of(sourceTask));
        when(taskRepository.save(any(TaskNode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencyRepository.findByParent_WorkflowVersion_Id(11L)).thenReturn(List.of());

        WorkflowVersionService service = new WorkflowVersionService(
                versionRepository, workflowRepository, taskRepository, dependencyRepository, workflowGraphService);
        WorkflowVersion newVersion = service.createVersion(7L);

        ArgumentCaptor<TaskNode> taskCaptor = ArgumentCaptor.forClass(TaskNode.class);
        verify(taskRepository).save(taskCaptor.capture());
        inOrder(workflowRepository, versionRepository)
                .verify(workflowRepository).findByIdForUpdate(7L);
        inOrder(workflowRepository, versionRepository)
                .verify(versionRepository).findTopByWorkflow_IdOrderByVersionNumberDesc(7L);
        TaskNode copiedTask = taskCaptor.getValue();
        assertEquals(newVersion, copiedTask.getWorkflowVersion());
        assertEquals("SHELL", copiedTask.getPluginType());
        assertEquals("{\"command\":\"echo ok\"}", copiedTask.getConfigurationJson());
        assertEquals("Readable test", copiedTask.getDisplayName());
        assertEquals(12.0, copiedTask.getXPosition());
        assertEquals(34.0, copiedTask.getYPosition());
    }
}
