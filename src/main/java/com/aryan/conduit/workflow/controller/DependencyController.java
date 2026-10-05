package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.workflow.dto.CreateDependencyRequest;
import com.aryan.conduit.workflow.dto.UpdateDependencyRequest;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.service.DependencyService;
import com.aryan.conduit.workflow.service.WorkflowGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.core.Authentication;
import com.aryan.conduit.security.service.WorkflowOwnershipService;

@RestController
@RequestMapping("/api/dependencies")
@RequiredArgsConstructor
public class DependencyController {
    private final WorkflowGraphService workflowGraphService;
    private final DependencyService dependencyService;
    private final WorkflowOwnershipService ownership;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void createDependency(@RequestBody CreateDependencyRequest request, Authentication principal) {
        if (request != null && principal.getAuthorities().stream().noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            ownership.requireTaskOwner(request.parentTaskId(), principal.getName());
            ownership.requireTaskOwner(request.childTaskId(), principal.getName());
        }
        dependencyService.createDependency(request);
    }

    @GetMapping("/{versionId}/roots")
    public List<Long> roots(@PathVariable("versionId") Long versionId) {
        return workflowGraphService.getRootTasks(versionId);
    }

    @PutMapping("/{id}")
    public Dependency updateDependency(@PathVariable Long id,
                                       @RequestBody UpdateDependencyRequest request) {
        return dependencyService.updateDependency(id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteDependency(@PathVariable Long id) {
        dependencyService.deleteDependency(id);
    }
}
