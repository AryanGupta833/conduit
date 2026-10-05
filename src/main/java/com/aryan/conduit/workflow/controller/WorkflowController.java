package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.service.ExecutionService;
import com.aryan.conduit.workflow.dto.*;
import com.aryan.conduit.workflow.service.WorkflowValidationService;
import com.aryan.conduit.workflow.service.WorkflowTransferService;
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
import org.springframework.security.core.Authentication;
import com.aryan.conduit.security.service.WorkflowOwnershipService;
import com.aryan.conduit.security.repository.ConduitUserRepository;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowGraphService workflowGraphService;
    private final ExecutionService executionService;
    private final WorkflowService workflowService;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowValidationService workflowValidationService;
    private final WorkflowTransferService workflowTransferService;
    private final WorkflowOwnershipService ownership;
    private final ConduitUserRepository users;

    @PostMapping
    public WorkflowResponse createWorkflow(@RequestBody CreateWorkflowRequest request, Authentication principal){
        Workflow workflow=workflowService.createWorkflow(request.name(),request.cronExpression());
        ownership.assignToCurrentUser(workflow.getId(), principal.getName());
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
        WorkflowVersion version=workflowVersionRepository.findTopByWorkflow_IdAndPublishedTrueOrderByVersionNumberDesc(id).orElseThrow(()->new IllegalStateException("No published version found"));
        return workflowGraphService.generateExecutionPlan(version.getId());
    }

    @PostMapping("/{id}/validate")
    public WorkflowValidationResponse validateWorkflow(@PathVariable Long id) {
        return workflowValidationService.validate(id);
    }
    @PostMapping("/{id}/execute")
    public Long executeWorkflow(@PathVariable Long id){
        return executionService.startWorkflow(id);
    }

    @PostMapping("/{id}/execute-with-options")
    public Long executeWorkflowWithOptions(@PathVariable Long id,
                                           @RequestBody(required = false) StartWorkflowRequest request) {
        WorkflowVersion version = workflowVersionRepository
                .findTopByWorkflow_IdAndPublishedTrueOrderByVersionNumberDesc(id)
                .orElseThrow(() -> new IllegalStateException("No published version found"));
        return executionService.startWorkflowVersion(version.getId(), request == null ? null : request.deadlineSeconds());
    }

    @PutMapping("/{id}/concurrency")
    @org.springframework.transaction.annotation.Transactional
    public void setConcurrencyLimit(@PathVariable Long id, @RequestBody WorkflowConcurrencyRequest request,
                                   Authentication principal) {
        boolean admin = principal.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) ownership.requireWorkflowOwner(id, principal.getName());
        Integer limit = request.maxConcurrentExecutions();
        if (limit == null || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("maxConcurrentExecutions must be between 1 and 1000");
        Workflow workflow = workflowRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
        workflow.setMaxConcurrentExecutions(limit);
        workflowRepository.save(workflow);
    }

    @PutMapping("/{id}/priority")
    @org.springframework.transaction.annotation.Transactional
    public void setWorkflowPriority(@PathVariable Long id, @RequestBody WorkflowPriorityRequest request,
                                   Authentication principal) {
        boolean admin = principal.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) ownership.requireWorkflowOwner(id, principal.getName());
        Integer priority = request.priority();
        if (priority == null || priority < -10 || priority > 10)
            throw new IllegalArgumentException("priority must be between -10 and 10");
        Workflow workflow = workflowRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
        workflow.setPriority(priority);
        workflowRepository.save(workflow);
    }

    @PostMapping("/import")
    public Long importWorkflow(@RequestBody WorkflowDefinition definition, Authentication principal) {
        Long id = workflowTransferService.importDefinition(definition);
        ownership.assignToCurrentUser(id, principal.getName());
        return id;
    }

    @GetMapping("/{id}/export")
    public WorkflowDefinition exportWorkflow(@PathVariable Long id) {
        return workflowTransferService.export(id);
    }

    @PostMapping("/{id}/clone")
    public Long cloneWorkflow(@PathVariable Long id, Authentication principal) {
        Long cloned = workflowTransferService.cloneWorkflow(id);
        ownership.assignToCurrentUser(cloned, principal.getName());
        return cloned;
    }

    @DeleteMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public void deleteWorkflow(@PathVariable Long id, Authentication principal) {
        boolean admin = principal.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) ownership.requireWorkflowOwner(id, principal.getName());
        workflowService.deleteWorkflow(id);
    }
    @GetMapping("/{id}/stages")
    public List<List<Long>> getStages(@PathVariable Long id){
        WorkflowVersion version=workflowVersionRepository.findTopByWorkflow_IdAndPublishedTrueOrderByVersionNumberDesc(id).orElseThrow(()->new IllegalStateException("No published version found"));

        return workflowGraphService.generateExecutionStages(version.getId());
    }
    @GetMapping("/{id}/graph")
    public WorkflowGraphResponse getGraph(@PathVariable Long id){
        WorkflowVersion version=workflowVersionRepository.findByWorkflow_IdAndLatestTrue(id)
                .or(() -> workflowVersionRepository.findTopByWorkflow_IdOrderByVersionNumberDesc(id))
                .orElseThrow(()->new IllegalStateException("Workflow version not found"));
        return workflowGraphService.getWorkflowGraph(version.getId());
    }
    @GetMapping
    public List<WorkflowResponse> getWorkflows(Authentication principal) {
        boolean admin = principal.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (admin) return workflowService.getAllWorkflows();
        Long ownerId = users.findByEmailIgnoreCase(principal.getName()).orElseThrow().getId();
        return workflowService.getWorkflowsOwnedBy(ownerId);
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
