package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.workflow.dto.WorkflowCredentialRequest;
import com.aryan.conduit.workflow.dto.WorkflowCredentialResponse;
import com.aryan.conduit.workflow.service.CredentialReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workflows/{workflowId}/credentials")
public class WorkflowCredentialController {
    private final CredentialReferenceService service;

    @GetMapping public List<WorkflowCredentialResponse> list(@PathVariable Long workflowId) { return service.list(workflowId); }
    @PostMapping public WorkflowCredentialResponse create(@PathVariable Long workflowId, @RequestBody WorkflowCredentialRequest request, Authentication actor, HttpServletRequest http) { return service.create(workflowId, request, actor.getName(), http.getRemoteAddr()); }
    @PutMapping("/{credentialId}") public WorkflowCredentialResponse update(@PathVariable Long workflowId, @PathVariable Long credentialId, @RequestBody WorkflowCredentialRequest request, Authentication actor, HttpServletRequest http) { return service.update(workflowId, credentialId, request, actor.getName(), http.getRemoteAddr()); }
    @DeleteMapping("/{credentialId}") public Map<String, String> delete(@PathVariable Long workflowId, @PathVariable Long credentialId, Authentication actor, HttpServletRequest http) { service.delete(workflowId, credentialId, actor.getName(), http.getRemoteAddr()); return Map.of("status", "deleted"); }
}
