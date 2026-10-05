package com.aryan.conduit.workflow.service;

import com.aryan.conduit.workflow.dto.CreateTaskRequest;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TaskServiceTest {
    @Test
    void refusesToMutatePublishedVersion() {
        TaskNodeRepository tasks = mock(TaskNodeRepository.class);
        WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
        DependencyRepository dependencies = mock(DependencyRepository.class);
        WorkflowRepository workflows = mock(WorkflowRepository.class);
        WorkflowVersion published = WorkflowVersion.builder().id(2L).published(true).build();
        when(workflows.findByIdForUpdate(1L)).thenReturn(Optional.of(
                com.aryan.conduit.workflow.entity.Workflow.builder().id(1L).build()));
        when(versions.findByWorkflow_IdAndLatestTrue(1L)).thenReturn(Optional.of(published));

        TaskService service = new TaskService(tasks, versions, dependencies, workflows);
        assertThrows(IllegalStateException.class, () -> service.createTask(
                new CreateTaskRequest(1L, "task", 30, 1, "SHELL", "{}", 0.0, 0.0)));
        verify(tasks, never()).save(any());
    }
}
