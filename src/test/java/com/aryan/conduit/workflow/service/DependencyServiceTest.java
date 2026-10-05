package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.DependencyCondition;
import com.aryan.conduit.workflow.dto.UpdateDependencyRequest;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DependencyServiceTest {
    @Test
    void rejectsCrossVersionUpdate() {
        Workflow workflow = Workflow.builder().id(1L).build();
        WorkflowVersion first = WorkflowVersion.builder().id(11L).workflow(workflow).published(false).build();
        WorkflowVersion second = WorkflowVersion.builder().id(12L).workflow(workflow).published(false).build();
        TaskNode parent = TaskNode.builder().id(101L).workflowVersion(first).build();
        TaskNode existingChild = TaskNode.builder().id(102L).workflowVersion(first).build();
        TaskNode otherVersionChild = TaskNode.builder().id(103L).workflowVersion(second).build();
        Dependency dependency = Dependency.builder().id(1L).parent(parent).child(existingChild).build();
        DependencyRepository dependencies = mock(DependencyRepository.class);
        TaskNodeRepository tasks = mock(TaskNodeRepository.class);
        WorkflowRepository workflows = mock(WorkflowRepository.class);
        when(dependencies.findById(1L)).thenReturn(Optional.of(dependency));
        when(tasks.findById(101L)).thenReturn(Optional.of(parent));
        when(tasks.findById(103L)).thenReturn(Optional.of(otherVersionChild));
        when(workflows.findByIdForUpdate(1L)).thenReturn(Optional.of(workflow));

        DependencyService service = new DependencyService(dependencies, tasks, workflows,
                mock(WorkflowGraphService.class));
        assertThrows(IllegalArgumentException.class, () -> service.updateDependency(1L,
                new UpdateDependencyRequest(101L, 103L, DependencyCondition.ALWAYS, null)));
        verify(dependencies, never()).save(any());
    }
}
