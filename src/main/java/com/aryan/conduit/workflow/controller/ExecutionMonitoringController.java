package com.aryan.conduit.workflow.controller;


import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.service.ExecutionMonitoringService;
import com.aryan.conduit.workflow.dto.ExecutionLogResponse;
import com.aryan.conduit.workflow.dto.TaskExecutionResponse;
import com.aryan.conduit.workflow.dto.WorkflowExecutionDetailResponse;
import com.aryan.conduit.workflow.dto.WorkflowExecutionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class ExecutionMonitoringController {

    private final ExecutionMonitoringService executionMonitoringService;

    @GetMapping
    public List<WorkflowExecutionResponse> getExecutions(){
        return executionMonitoringService.getExecutions();
    }

    @GetMapping("/{id}")
    public WorkflowExecutionDetailResponse getExecution(@PathVariable Long id){
        return executionMonitoringService.getExecution(id);
    }

    @GetMapping("/{id}/tasks")
    public List<TaskExecutionResponse> getTasks(@PathVariable Long id){
        return executionMonitoringService.getTasks(id);
    }

    @GetMapping("/{id}/logs")
    public List<ExecutionLogResponse> getLogs(@PathVariable Long id){
        return executionMonitoringService.getLogs(id);
    }
}
