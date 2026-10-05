package com.aryan.conduit.workflow.service;

import com.aryan.conduit.common.util.DagValidator;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class WorkflowGraphServiceTest {
    @Test
    void rejectsDependencyPointingOutsideSelectedWorkflowVersion() {
        TaskNodeRepository tasks = mock(TaskNodeRepository.class);
        DependencyRepository dependencies = mock(DependencyRepository.class);
        WorkflowVersion version = WorkflowVersion.builder().id(3L).build();
        TaskNode parent = TaskNode.builder().id(10L).workflowVersion(version).build();
        TaskNode foreignChild = TaskNode.builder().id(20L)
                .workflowVersion(WorkflowVersion.builder().id(4L).build()).build();
        Dependency edge = Dependency.builder().parent(parent).child(foreignChild).build();
        when(tasks.findByWorkflowVersion_Id(3L)).thenReturn(List.of(parent));
        when(dependencies.findByParent_WorkflowVersion_Id(3L)).thenReturn(List.of(edge));

        WorkflowGraphService service = new WorkflowGraphService(tasks, dependencies, new DagValidator());
        assertThrows(IllegalStateException.class, () -> service.validateWorkflow(3L));
    }
}
