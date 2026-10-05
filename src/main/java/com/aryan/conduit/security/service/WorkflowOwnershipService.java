package com.aryan.conduit.security.service;

import com.aryan.conduit.security.repository.ConduitUserRepository;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowOwnershipService {
    private final WorkflowRepository workflows;
    private final ConduitUserRepository users;
    private final TaskNodeRepository tasks;
    private final WorkflowExecutionRepository executions;

    @Transactional
    public void assignToCurrentUser(Long workflowId, String email) {
        var owner = users.findByEmailIgnoreCase(email).orElseThrow(() -> new IllegalArgumentException("Current account not found"));
        Workflow workflow = workflows.findByIdForUpdate(workflowId).orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
        if (workflow.getOwner() != null && !workflow.getOwner().getId().equals(owner.getId()))
            throw new IllegalStateException("Workflow already has an owner");
        workflow.setOwner(owner);
        workflows.save(workflow);
    }

    @Transactional
    public void assignUnownedTo(Long ownerId) {
        var owner = users.findById(ownerId).orElseThrow();
        workflows.findByOwnerIsNull().forEach(workflow -> {
            workflow.setOwner(owner);
            workflows.save(workflow);
        });
    }

    @Transactional(readOnly = true)
    public void requireTaskOwner(Long taskId, String email) {
        var user = users.findByEmailIgnoreCase(email).orElseThrow();
        var task = tasks.findById(taskId).orElseThrow(() -> new IllegalArgumentException("Task not found"));
        var owner = task.getWorkflowVersion().getWorkflow().getOwner();
        if (owner == null || !owner.getId().equals(user.getId())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Resource not found");
    }

    @Transactional(readOnly = true)
    public void requireWorkflowOwner(Long workflowId, String email) {
        var user = users.findByEmailIgnoreCase(email).orElseThrow();
        var workflow = workflows.findById(workflowId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Resource not found"));
        if (workflow.getOwner() == null || !workflow.getOwner().getId().equals(user.getId()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Resource not found");
    }

    @Transactional(readOnly = true)
    public boolean ownsExecution(Long executionId, String email) {
        var user = users.findByEmailIgnoreCase(email).orElse(null);
        var execution = executions.findById(executionId).orElse(null);
        return user != null && execution != null && execution.getWorkflowVersion().getWorkflow().getOwner() != null
                && execution.getWorkflowVersion().getWorkflow().getOwner().getId().equals(user.getId());
    }
}
