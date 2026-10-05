package com.aryan.conduit.security.service;

import com.aryan.conduit.execution.repository.HumanTaskRequestRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.security.repository.ConduitUserRepository;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Enforces workflow ownership across nested API resources, not only workflow routes. */
@Component
@RequiredArgsConstructor
public class WorkflowOwnershipFilter extends OncePerRequestFilter {
    private final ConduitUserRepository users;
    private final WorkflowRepository workflows;
    private final WorkflowVersionRepository versions;
    private final TaskNodeRepository tasks;
    private final DependencyRepository dependencies;
    private final WorkflowExecutionRepository executions;
    private final HumanTaskRequestRepository humanTasks;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || isAdmin(authentication)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            Set<Long> referenced = resolveWorkflowIds(request.getServletPath());
            if (referenced.isEmpty()) {
                chain.doFilter(request, response);
                return;
            }
            var user = users.findByEmailIgnoreCase(authentication.getName()).orElse(null);
            if (user == null || !allOwned(referenced, user.getId())) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Resource not found");
                return;
            }
            chain.doFilter(request, response);
        } catch (RuntimeException databaseOrMappingFailure) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Unable to verify resource access");
        }
    }

    private boolean allOwned(Set<Long> ids, Long userId) {
        for (Long id : ids) {
            var workflow = workflows.findById(id).orElse(null);
            if (workflow == null || workflow.getOwner() == null || !workflow.getOwner().getId().equals(userId)) return false;
        }
        return true;
    }

    private Set<Long> resolveWorkflowIds(String path) {
        String[] p = path.split("/");
        Set<Long> result = new LinkedHashSet<>();
        if (p.length >= 4 && p[1].equals("api") && p[2].equals("workflows")) add(result, p[3]);
        else if (p.length >= 4 && p[1].equals("api") && p[2].equals("workflow-versions")) {
            if (p.length == 4 || p[3].equals("latest")) {
                add(result, p[3].equals("latest") ? at(p, 4) : p[3]);
            } else if (p.length >= 6 && p[4].equals("rollback")) {
                add(result, p[3]);
                addVersionWorkflow(result, p[5]);
            } else {
                addVersionWorkflow(result, p[3]);
                if (p.length >= 6 && p[4].equals("diff")) addVersionWorkflow(result, p[5]);
            }
        } else if (p.length >= 4 && p[1].equals("api") && p[2].equals("tasks")) {
            parse(p[3]).flatMap(tasks::findById).map(task -> task.getWorkflowVersion().getWorkflow().getId()).ifPresent(result::add);
        } else if (p.length >= 4 && p[1].equals("api") && p[2].equals("dependencies")) {
            if (p.length == 5 && p[4].equals("roots")) addVersionWorkflow(result, p[3]);
            else parse(p[3]).flatMap(dependencies::findById).map(edge -> edge.getParent().getWorkflowVersion().getWorkflow().getId()).ifPresent(result::add);
        } else if (p.length >= 4 && p[1].equals("api") && p[2].equals("executions")) {
            parse(p[3]).flatMap(executions::findById).map(run -> run.getWorkflowVersion().getWorkflow().getId()).ifPresent(result::add);
            if (p.length >= 6 && p[4].equals("compare"))
                parse(p[5]).flatMap(executions::findById).map(run -> run.getWorkflowVersion().getWorkflow().getId()).ifPresent(result::add);
        } else if (p.length >= 4 && p[1].equals("api") && p[2].equals("human-tasks")) {
            parse(p[3]).flatMap(humanTasks::findById).map(task -> task.getWorkflowExecution().getWorkflowVersion().getWorkflow().getId()).ifPresent(result::add);
        } else if (p.length >= 5 && p[1].equals("api") && p[2].equals("logs") && p[3].equals("workflow")) {
            addExecutionWorkflow(result, p[4]);
        } else if (p.length >= 4 && p[1].equals("api") && p[2].equals("context")) {
            addExecutionWorkflow(result, p[3]);
        } else if (p.length >= 4 && p[1].equals("api") && p[2].equals("output")) {
            addExecutionWorkflow(result, p[3].equals("test") ? at(p, 4) : p[3]);
        }
        return result;
    }

    private void addExecutionWorkflow(Set<Long> result, String rawId) {
        parse(rawId).flatMap(executions::findById).map(run -> run.getWorkflowVersion().getWorkflow().getId()).ifPresent(result::add);
    }
    private void addVersionWorkflow(Set<Long> result, String rawId) {
        parse(rawId).flatMap(versions::findById).map(version -> version.getWorkflow().getId()).ifPresent(result::add);
    }
    private static String at(String[] parts, int index) { return index < parts.length ? parts[index] : ""; }
    private static java.util.Optional<Long> parse(String raw) {
        try { return java.util.Optional.of(Long.parseLong(raw)); } catch (RuntimeException ignored) { return java.util.Optional.empty(); }
    }
    private static void add(Set<Long> target, String raw) { parse(raw).ifPresent(target::add); }
    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
