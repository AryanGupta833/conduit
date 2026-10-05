package com.aryan.conduit.workflow.controller;


import com.aryan.conduit.execution.service.ExecutionMonitoringService;
import com.aryan.conduit.execution.dto.TaskExecutionDetailsResponse;
import com.aryan.conduit.execution.service.TaskExecutionDetailsService;
import com.aryan.conduit.workflow.dto.ExecutionLogResponse;
import com.aryan.conduit.workflow.dto.TaskExecutionResponse;
import com.aryan.conduit.workflow.dto.WorkflowExecutionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import org.springframework.security.core.Authentication;
import com.aryan.conduit.security.repository.ConduitUserRepository;
import com.aryan.conduit.security.entity.AccountRole;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class ExecutionMonitoringController {

    private final ExecutionMonitoringService executionMonitoringService;
    private final TaskExecutionDetailsService taskExecutionDetailsService;
    private final ConduitUserRepository users;

    @GetMapping
    public List<WorkflowExecutionResponse> getExecutions(Authentication principal){
        var user = users.findByEmailIgnoreCase(principal.getName()).orElseThrow();
        return user.getRole() == AccountRole.ADMIN ? executionMonitoringService.getExecutions()
                : executionMonitoringService.getExecutionsForOwner(user.getId());
    }

    @GetMapping("/{id}/tasks")
    public List<TaskExecutionResponse> getTasks(@PathVariable Long id){
        return executionMonitoringService.getTasks(id);
    }

    @GetMapping("/{id}/logs")
    public List<ExecutionLogResponse> getLogs(@PathVariable Long id){
        return executionMonitoringService.getLogs(id);
    }

    @GetMapping("/{executionId}/tasks/{taskExecutionId}/details")
    public TaskExecutionDetailsResponse taskDetails(@PathVariable Long executionId, @PathVariable Long taskExecutionId) {
        return taskExecutionDetailsService.get(executionId, taskExecutionId);
    }
}
