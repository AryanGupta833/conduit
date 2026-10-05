package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.JoinCondition;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.dto.UpdateTaskRequest;
import com.aryan.conduit.workflow.dto.CreateTaskRequest;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aryan.conduit.plugin.security.InlineSecretConfigurationGuard;

@Service
public class TaskService {

    private final TaskNodeRepository taskNodeRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final DependencyRepository dependencyRepository;
    private final WorkflowRepository workflowRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public TaskService(TaskNodeRepository taskNodeRepository, WorkflowVersionRepository workflowVersionRepository,
                       DependencyRepository dependencyRepository, WorkflowRepository workflowRepository,
                       ObjectMapper objectMapper) {
        this.taskNodeRepository = taskNodeRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.dependencyRepository = dependencyRepository;
        this.workflowRepository = workflowRepository;
        this.objectMapper = objectMapper;
    }

    /** Compatibility constructor retained for unit tests and external integrations. */
    public TaskService(TaskNodeRepository taskNodeRepository, WorkflowVersionRepository workflowVersionRepository,
                       DependencyRepository dependencyRepository, WorkflowRepository workflowRepository) {
        this(taskNodeRepository, workflowVersionRepository, dependencyRepository, workflowRepository, new ObjectMapper());
    }

    @Transactional
    public TaskNode createTask(CreateTaskRequest request) {

        InlineSecretConfigurationGuard.validateJson(request.configurationJson(), objectMapper);

        workflowRepository.findByIdForUpdate(request.workflowId())
                .orElseThrow(() -> new RuntimeException("Workflow not found"));

        WorkflowVersion version = workflowVersionRepository
                .findByWorkflow_IdAndLatestTrue(request.workflowId())
                .orElseThrow(() ->
                        new RuntimeException("Latest workflow version not found"));

        ensureDraft(version);
        validateName(request.name());
        if (taskNodeRepository.findByWorkflowVersion_IdAndName(version.getId(), request.name()).isPresent())
            throw new IllegalArgumentException("Task name already exists in this workflow version");

        TaskNode task = TaskNode.builder()
                .name(request.name())
                .workflowVersion(version)
                .timeoutSeconds(request.timeoutSeconds())
                .maxRetries(request.maxRetries())
                .pluginType(request.pluginType())
                .configurationJson(request.configurationJson())
                .joinCondition(JoinCondition.ALL_PARENTS)
                .build();

        return taskNodeRepository.save(task);
    }

    @Transactional
    public TaskNode updateTask(Long id, UpdateTaskRequest request) {

        if (request.configurationJson() != null) InlineSecretConfigurationGuard.validateJson(request.configurationJson(), objectMapper);

        TaskNode task = taskNodeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Task not found"));

        Long versionId = task.getWorkflowVersion().getId();
        Long workflowId = task.getWorkflowVersion().getWorkflow().getId();
        workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new RuntimeException("Workflow not found"));
        task = taskNodeRepository.findById(id).orElseThrow(() -> new RuntimeException("Task not found"));
        ensureDraft(task.getWorkflowVersion());

        if (request.name() != null) {
            validateName(request.name());
            var named = taskNodeRepository.findByWorkflowVersion_IdAndName(versionId, request.name());
            if (named.isPresent() && !named.get().getId().equals(id))
                throw new IllegalArgumentException("Task name already exists in this workflow version");
            task.setName(request.name());
        }

        if (request.timeoutSeconds() != null)
            task.setTimeoutSeconds(request.timeoutSeconds());

        if (request.maxRetries() != null)
            task.setMaxRetries(request.maxRetries());

        if (request.joinCondition() != null)
            task.setJoinCondition(request.joinCondition());

        if (request.pluginType() != null)
            task.setPluginType(request.pluginType());

        if (request.configurationJson() != null)
            task.setConfigurationJson(request.configurationJson());

        if (request.xPosition() != null)
            task.setXPosition(request.xPosition());

        if (request.yPosition() != null)
            task.setYPosition(request.yPosition());

        return taskNodeRepository.save(task);
    }

    @Transactional
    public void deleteTask(Long taskId) {

        TaskNode task = taskNodeRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Task not found"));
        workflowRepository.findByIdForUpdate(task.getWorkflowVersion().getWorkflow().getId())
                .orElseThrow(() -> new RuntimeException("Workflow not found"));
        task = taskNodeRepository.findById(taskId).orElseThrow(() -> new RuntimeException("Task not found"));
        ensureDraft(task.getWorkflowVersion());

        dependencyRepository.deleteAll(
                dependencyRepository.findByChild_Id(taskId)
        );

        dependencyRepository.deleteAll(
                dependencyRepository.findByParent_Id(taskId)
        );

        taskNodeRepository.deleteById(taskId);
    }

    private void ensureDraft(WorkflowVersion version) {
        if (Boolean.TRUE.equals(version.getPublished()))
            throw new IllegalStateException("Published workflow versions are immutable; create a draft version first");
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Task name is required");
    }
}
