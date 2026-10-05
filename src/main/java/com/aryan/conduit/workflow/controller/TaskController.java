package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.workflow.dto.CreateTaskRequest;
import com.aryan.conduit.workflow.dto.UpdateTaskRequest;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import com.aryan.conduit.security.service.WorkflowOwnershipService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;
    private final WorkflowOwnershipService ownership;

    @PostMapping
    public Long createTask(@RequestBody CreateTaskRequest request, Authentication principal){
        if (principal.getAuthorities().stream().noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())))
            ownership.requireWorkflowOwner(request.workflowId(), principal.getName());
        return taskService.createTask(request).getId();
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
