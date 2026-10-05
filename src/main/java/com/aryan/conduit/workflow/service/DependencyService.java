package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.DependencyCondition;
import com.aryan.conduit.workflow.dto.CreateDependencyRequest;
import com.aryan.conduit.workflow.dto.UpdateDependencyRequest;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DependencyService {
    private final DependencyRepository dependencyRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowGraphService workflowGraphService;

    @Transactional
    public Dependency createDependency(CreateDependencyRequest request) {
        if (request == null) throw new IllegalArgumentException("Dependency request is required");
        TaskNode parent = findTask(request.parentTaskId(), "Parent");
        TaskNode child = findTask(request.childTaskId(), "Child");
        requireSameVersion(parent, child);
        workflowRepository.findByIdForUpdate(parent.getWorkflowVersion().getWorkflow().getId())
                .orElseThrow(() -> new RuntimeException("Workflow not found"));
        parent = findTask(request.parentTaskId(), "Parent");
        child = findTask(request.childTaskId(), "Child");
        requireSameVersion(parent, child);
        ensureDraft(parent.getWorkflowVersion());
        validateEdge(request.condition(), request.expression());
        if (dependencyRepository.existsByParent_IdAndChild_Id(parent.getId(), child.getId()))
            throw new IllegalStateException("Dependency already exists");

        Dependency dependency = dependencyRepository.save(Dependency.builder()
                .parent(parent).child(child).condition(request.condition()).expression(request.expression()).build());
        workflowGraphService.validateWorkflow(parent.getWorkflowVersion().getId());
        return dependency;
    }

    @Transactional
    public Dependency updateDependency(Long dependencyId, UpdateDependencyRequest request) {
        if (request == null) throw new IllegalArgumentException("Dependency request is required");
        Dependency dependency = dependencyRepository.findById(dependencyId)
                .orElseThrow(() -> new RuntimeException("Dependency not found"));
        Long versionId = dependency.getParent().getWorkflowVersion().getId();
        Long workflowId = dependency.getParent().getWorkflowVersion().getWorkflow().getId();
        workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new RuntimeException("Workflow not found"));
        dependency = dependencyRepository.findById(dependencyId)
                .orElseThrow(() -> new RuntimeException("Dependency not found"));
        ensureDraft(dependency.getParent().getWorkflowVersion());

        TaskNode parent = findTask(request.parentTaskId(), "Parent");
        TaskNode child = findTask(request.childTaskId(), "Child");
        if (!versionId.equals(parent.getWorkflowVersion().getId())
                || !versionId.equals(child.getWorkflowVersion().getId()))
            throw new IllegalArgumentException("Dependency tasks must belong to the dependency's workflow version");
        requireSameVersion(parent, child);
        validateEdge(request.condition(), request.expression());
        if (dependencyRepository.existsByParent_IdAndChild_Id(parent.getId(), child.getId())
                && !(dependency.getParent().getId().equals(parent.getId())
                && dependency.getChild().getId().equals(child.getId())))
            throw new IllegalStateException("Dependency already exists");

        dependency.setParent(parent);
        dependency.setChild(child);
        dependency.setCondition(request.condition());
        dependency.setExpression(request.expression());
        Dependency saved = dependencyRepository.save(dependency);
        workflowGraphService.validateWorkflow(versionId);
        return saved;
    }

    @Transactional
    public void deleteDependency(Long dependencyId) {
        Dependency dependency = dependencyRepository.findById(dependencyId)
                .orElseThrow(() -> new RuntimeException("Dependency not found"));
        Long versionId = dependency.getParent().getWorkflowVersion().getId();
        Long workflowId = dependency.getParent().getWorkflowVersion().getWorkflow().getId();
        workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new RuntimeException("Workflow not found"));
        dependency = dependencyRepository.findById(dependencyId)
                .orElseThrow(() -> new RuntimeException("Dependency not found"));
        ensureDraft(dependency.getParent().getWorkflowVersion());
        dependencyRepository.delete(dependency);
        workflowGraphService.validateWorkflow(versionId);
    }

    private TaskNode findTask(Long id, String role) {
        if (id == null) throw new IllegalArgumentException(role + " task ID is required");
        return taskNodeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException(role + " task not found"));
    }

    private void requireSameVersion(TaskNode parent, TaskNode child) {
        if (parent.getId().equals(child.getId()))
            throw new IllegalArgumentException("Task cannot depend on itself");
        if (!parent.getWorkflowVersion().getId().equals(child.getWorkflowVersion().getId()))
            throw new IllegalArgumentException("Tasks belong to different workflow versions");
    }

    private void validateEdge(DependencyCondition condition, String expression) {
        if (condition == null) throw new IllegalArgumentException("Dependency condition is required");
        if (condition == DependencyCondition.EXPRESSION && (expression == null || expression.isBlank()))
            throw new IllegalArgumentException("Expression dependency requires an expression");
    }

    private void ensureDraft(WorkflowVersion version) {
        if (Boolean.TRUE.equals(version.getPublished()))
            throw new IllegalStateException("Published workflow versions are immutable; create a draft version first");
    }
}
