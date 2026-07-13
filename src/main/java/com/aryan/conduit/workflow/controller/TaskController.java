package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.entity.JoinCondition;
import com.aryan.conduit.workflow.dto.CreateTaskRequest;
import com.aryan.conduit.workflow.dto.UpdateTaskRequest;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.aryan.conduit.workflow.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.config.Task;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskNodeRepository taskNodeRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final TaskService taskService;

    @PostMapping
    public Long createTask(@RequestBody CreateTaskRequest request){
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
                .xPosition(request.xPosition())
                .yPosition(request.yPosition())
                .joinCondition(JoinCondition.ALL_PARENTS)
                .build();

        return taskNodeRepository.save(task).getId();
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateTask(
            @PathVariable Long id,
            @RequestBody UpdateTaskRequest request) {

         taskService.updateTask(id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteTask(
            @PathVariable Long id) {

        taskService.deleteTask(id);
    }
}
