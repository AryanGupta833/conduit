package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.service.ExecutionService;
import com.aryan.conduit.workflow.dto.CreateWorkflowRequest;
import com.aryan.conduit.workflow.dto.ExecutionPlan;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.service.WorkflowGraphService;
import com.aryan.conduit.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowGraphService workflowGraphService;
    private final ExecutionService executionService;
    private final WorkflowService workflowService;

    @PostMapping
    public Workflow createWorkflow(@RequestBody CreateWorkflowRequest request){
        return workflowService.createWorkflow(request.name());
    }

    @GetMapping("/{id}/plan")
    public ExecutionPlan getPlan(@PathVariable Long id){
        return workflowGraphService.generateExecutionPlan(id);
    }
    @PostMapping("/{id}/execute")
    public Long executeWorkflow(@PathVariable Long id){
        return executionService.startWorkflow(id);
    }
}
