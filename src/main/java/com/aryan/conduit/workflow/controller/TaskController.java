package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.workflow.dto.CreateTaskRequest;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
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

    @PostMapping
    public TaskNode createTask(@RequestBody CreateTaskRequest request){
        Workflow workflow=workflowRepository.findById(request.workflowId())
                .orElseThrow(()->new RuntimeException("Workflow not found"));
        TaskNode task=TaskNode.builder().
                name(request.name()).
                workflow(workflow).
        build();

        return taskNodeRepository.save(task)
;    }
}
