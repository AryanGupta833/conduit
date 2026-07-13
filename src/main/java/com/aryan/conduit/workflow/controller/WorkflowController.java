package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.service.ExecutionService;
import com.aryan.conduit.workflow.dto.*;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.aryan.conduit.workflow.service.WorkflowGraphService;
import com.aryan.conduit.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowGraphService workflowGraphService;
    private final ExecutionService executionService;
    private final WorkflowService workflowService;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final WorkflowRepository workflowRepository;

    @PostMapping
    public WorkflowResponse createWorkflow(@RequestBody CreateWorkflowRequest request){
        Workflow workflow=workflowService.createWorkflow(request.name(),request.cronExpression());
        LocalDateTime nextScheduledRun = null;

        if (workflow.getCronExpression() != null &&
                !workflow.getCronExpression().isBlank()) {

            CronExpression cron =
                    CronExpression.parse(workflow.getCronExpression());

            LocalDateTime base =
                    workflow.getLastScheduledRun() == null
                            ? workflow.getCreatedAt()
                            : workflow.getLastScheduledRun();

            nextScheduledRun = cron.next(base);
        }
        return new WorkflowResponse(workflow.getId(),
                workflow.getName(),
                workflow.getStatus(),
                workflow.getActive(),
                workflow.getCronExpression(),
                0,
                workflow.getCreatedAt(),
                workflow.getLastScheduledRun(),
                nextScheduledRun
                        );

    }

    @GetMapping("/{id}/plan")
    public ExecutionPlan getPlan(@PathVariable Long id){
        WorkflowVersion version=workflowVersionRepository.findByWorkflow_IdAndLatestTrue(id).orElseThrow(()->new IllegalStateException("No published version found"));
        return workflowGraphService.generateExecutionPlan(version.getId());
    }
    @PostMapping("/{id}/execute")
    public Long executeWorkflow(@PathVariable Long id){
        return executionService.startWorkflow(id);
    }
    @GetMapping("/{id}/stages")
    public List<List<Long>> getStages(@PathVariable Long id){
        WorkflowVersion version=workflowVersionRepository.findByWorkflow_IdAndLatestTrue(id).orElseThrow(()->new IllegalStateException("No published version found"));

        return workflowGraphService.generateExecutionStages(version.getId());
    }
    @GetMapping("/{id}/graph")
    public WorkflowGraphResponse getGraph(@PathVariable Long id){
        WorkflowVersion version=workflowVersionRepository.findByWorkflow_IdAndLatestTrue(id).orElseThrow(()->new IllegalStateException("No published version found"));
        return workflowGraphService.getWorkflowGraph(version.getId());
    }
    @GetMapping
    public List<WorkflowResponse> getWorkflows() {
        return workflowService.getAllWorkflows();
    }

    @PutMapping("/{id}/active")
    public WorkflowResponse updateWorkflowActive(@PathVariable Long id, @RequestBody UpdateWorkflowActiveRequest reques){
        return workflowService.updateWorkflowActive(id,reques.active());
    }

    @PostMapping("/{workflowId}/versions")
    public WorkflowVersionResponse createVersion(
            @PathVariable Long workflowId) {

        return workflowService.createNewVersion(workflowId);
    }
}
