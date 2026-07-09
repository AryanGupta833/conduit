package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.entity.JoinCondition;
import com.aryan.conduit.workflow.dto.CreateTaskRequest;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.config.Task;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskNodeRepository taskNodeRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;

    @PostMapping
    public TaskNode createTask(@RequestBody CreateTaskRequest request){
        WorkflowVersion version = workflowVersionRepository
                .findByWorkflow_IdAndLatestTrue(request.workflowId())
                .orElseThrow(() ->
                        new RuntimeException("No published workflow version found"));

        TaskNode task = TaskNode.builder()
                .name(request.name())
                .timeoutSeconds(request.timeoutSeconds())
                .maxRetries(request.maxRetries())
                .workflowVersion(version)
                .joinCondition(JoinCondition.ALL_PARENTS)
                .build();

        return taskNodeRepository.save(task);
    }
}
