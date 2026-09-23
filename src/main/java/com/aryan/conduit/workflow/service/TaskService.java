package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.JoinCondition;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.dto.UpdateTaskRequest;
import com.aryan.conduit.workflow.dto.CreateTaskRequest;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskNodeRepository taskNodeRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final DependencyRepository dependencyRepository;

    @Transactional
    public TaskNode createTask(CreateTaskRequest request) {

        WorkflowVersion version = workflowVersionRepository
                .findByWorkflow_IdAndLatestTrue(request.workflowId())
                .orElseThrow(() ->
                        new RuntimeException("Latest workflow version not found"));

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

        TaskNode task = taskNodeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Task not found"));

        if (request.name() != null)
            task.setName(request.name());

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

        if (!taskNodeRepository.existsById(taskId)) {
            throw new RuntimeException("Task not found");
        }

        dependencyRepository.deleteAll(
                dependencyRepository.findByChild_Id(taskId)
        );

        dependencyRepository.deleteAll(
                dependencyRepository.findByParent_Id(taskId)
        );

        taskNodeRepository.deleteById(taskId);
    }
}