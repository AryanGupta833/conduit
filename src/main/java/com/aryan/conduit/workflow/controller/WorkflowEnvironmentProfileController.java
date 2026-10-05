package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.workflow.dto.SelectWorkflowEnvironmentRequest;
import com.aryan.conduit.workflow.dto.WorkflowEnvironmentProfileRequest;
import com.aryan.conduit.workflow.dto.WorkflowEnvironmentProfileResponse;
import com.aryan.conduit.workflow.service.WorkflowEnvironmentProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workflows/{workflowId}/environments")
public class WorkflowEnvironmentProfileController {
    private final WorkflowEnvironmentProfileService service;

    @GetMapping
    public List<WorkflowEnvironmentProfileResponse> list(@PathVariable Long workflowId) { return service.list(workflowId); }

    @PostMapping
    public WorkflowEnvironmentProfileResponse create(@PathVariable Long workflowId, @RequestBody WorkflowEnvironmentProfileRequest request) { return service.create(workflowId, request); }

    @PutMapping("/{profileId}")
    public WorkflowEnvironmentProfileResponse update(@PathVariable Long workflowId, @PathVariable Long profileId, @RequestBody WorkflowEnvironmentProfileRequest request) { return service.update(workflowId, profileId, request); }

    @DeleteMapping("/{profileId}")
    public Map<String, String> delete(@PathVariable Long workflowId, @PathVariable Long profileId) { service.delete(workflowId, profileId); return Map.of("status", "deleted"); }

    @PutMapping("/selected")
    public Map<String, String> select(@PathVariable Long workflowId, @RequestBody SelectWorkflowEnvironmentRequest request) { service.select(workflowId, request.profileId()); return Map.of("status", request.profileId() == null ? "cleared" : "selected"); }
}
