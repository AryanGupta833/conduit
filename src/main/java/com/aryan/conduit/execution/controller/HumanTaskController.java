package com.aryan.conduit.execution.controller;

import com.aryan.conduit.execution.dto.HumanTaskActionRequest;
import com.aryan.conduit.execution.dto.HumanTaskResponse;
import com.aryan.conduit.execution.entity.HumanTaskStatus;
import com.aryan.conduit.execution.service.HumanTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.aryan.conduit.security.service.WorkflowOwnershipService;
import java.util.List;

@RestController @RequiredArgsConstructor @RequestMapping("/api/human-tasks")
public class HumanTaskController {
    private final HumanTaskService service;
    private final WorkflowOwnershipService ownership;
    @GetMapping public List<HumanTaskResponse> list(Authentication principal, @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Long workflowExecutionId,
                                                   @RequestParam(required = false) String assignee,
                                                   @RequestParam(required = false) String type) {
        if (!isAdmin(principal)) {
            if (assignee != null && !assignee.isBlank() && !assignee.equalsIgnoreCase(principal.getName()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view tasks assigned to your account");
            assignee = principal.getName();
        }
        List<HumanTaskResponse> tasks = service.list(status, workflowExecutionId, assignee, type);
        return isAdmin(principal) ? tasks : tasks.stream().filter(task -> ownership.ownsExecution(task.workflowExecutionId(), principal.getName())).toList();
    }
    @GetMapping("/{id}") public HumanTaskResponse get(Authentication principal, @PathVariable Long id) { return service.getAuthorized(id, principal); }
    @PostMapping("/{id}/approve") public HumanTaskResponse approve(Authentication principal, @PathVariable Long id, @RequestBody(required = false) HumanTaskActionRequest body) { return service.completeAuthorized(id, HumanTaskStatus.APPROVED, body == null ? null : body.comment(), null, principal); }
    @PostMapping("/{id}/reject") public HumanTaskResponse reject(Authentication principal, @PathVariable Long id, @RequestBody(required = false) HumanTaskActionRequest body) { return service.completeAuthorized(id, HumanTaskStatus.REJECTED, body == null ? null : body.comment(), null, principal); }
    @PostMapping("/{id}/respond") public HumanTaskResponse respond(Authentication principal, @PathVariable Long id, @RequestBody HumanTaskActionRequest body) { return service.completeAuthorized(id, HumanTaskStatus.RESPONDED, body == null ? null : body.comment(), body == null ? null : body.response(), principal); }

    private boolean isAdmin(Authentication principal) {
        return principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch("ROLE_ADMIN"::equals);
    }
}
