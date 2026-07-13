package com.aryan.conduit.workflow.controller;


import com.aryan.conduit.execution.service.ExecutionService;
import com.aryan.conduit.workflow.dto.WorkflowVersionDiffResponse;
import com.aryan.conduit.workflow.dto.WorkflowVersionResponse;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.service.WorkflowVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflow-versions")
@RequiredArgsConstructor
public class WorkflowVersionController {

    private final WorkflowVersionService workflowVersionService;
    private final ExecutionService executionService;


    @PostMapping("/{workflowId}")
    public WorkflowVersion createVersion(@PathVariable Long workflowId){
        return workflowVersionService.createVersion(workflowId);
    }

    @GetMapping("/{workflowId}")
    public List<WorkflowVersion> getVersions(@PathVariable Long workflowId){
        return workflowVersionService.getVersions(workflowId);
    }

    @PostMapping("/{versionId}/publish")
    public WorkflowVersion publishVersion(
            @PathVariable Long versionId) {

        return workflowVersionService.publishVersion(versionId);
    }

    @PostMapping("/{workflowId}/rollback/{versionId}")
    public WorkflowVersion rollback(
            @PathVariable Long workflowId,
            @PathVariable Long versionId) {

        return workflowVersionService.rollbackToVersion(
                workflowId,
                versionId
        );
    }

    @GetMapping
    public List<WorkflowVersionResponse> getAllVersions(){
        return workflowVersionService.getAllVersions();
    }

    @GetMapping("/latest/{workflowId}")
    public WorkflowVersion latest(
            @PathVariable Long workflowId) {

        return workflowVersionService
                .getLatestPublishedVersion(workflowId);
    }

    @PostMapping("/{versionId}/execute")
    public Long executeVersion(
            @PathVariable Long versionId) {

        return executionService.startWorkflowVersion(versionId);
    }

    @GetMapping("/{version1}/diff/{version2}")
    public WorkflowVersionDiffResponse compareVersions(
            @PathVariable Long version1,
            @PathVariable Long version2) {

        return workflowVersionService.compareVersions(
                version1,
                version2
        );
    }
}
