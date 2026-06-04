package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.service.ExecutionService;
import com.aryan.conduit.workflow.dto.CreateWorkflowRequest;
import com.aryan.conduit.workflow.dto.ExecutionPlan;
import com.aryan.conduit.workflow.dto.WorkflowGraphResponse;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.service.WorkflowGraphService;
import com.aryan.conduit.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowGraphService workflowGraphService;
    private final ExecutionService executionService;
    private final WorkflowService workflowService;

    @PostMapping
    public Workflow createWorkflow(@RequestBody CreateWorkflowRequest request){
        return workflowService.createWorkflow(request.name(),request.cronExpression());
    }

    @GetMapping("/{id}/plan")
    public ExecutionPlan getPlan(@PathVariable Long id){
        return workflowGraphService.generateExecutionPlan(id);
    }
    @PostMapping("/{id}/execute")
    public Long executeWorkflow(@PathVariable Long id){
        return executionService.startWorkflow(id);
    }
    @GetMapping("/{id}/stages")
    public List<List<Long>> getStages(@PathVariable Long id){
        return workflowGraphService.generateExecutionStages(id);
    }
    @GetMapping("/{id}/graph")
    public WorkflowGraphResponse getGraph(@PathVariable Long id){
        return workflowGraphService.getWorkflowGraph(id);
    }
}
