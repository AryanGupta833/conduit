package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.service.ExecutionGraphService;
import com.aryan.conduit.execution.service.ExecutionLogStreamService;
import com.aryan.conduit.execution.service.ExecutionService;
import com.aryan.conduit.execution.service.ExecutionSummaryService;
import com.aryan.conduit.execution.service.ExecutionComparisonService;
import com.aryan.conduit.execution.dto.ExecutionComparisonResponse;
import com.aryan.conduit.workflow.dto.ExecutionGraphResponse;
import com.aryan.conduit.workflow.dto.ExecutionResponse;
import com.aryan.conduit.workflow.dto.ExecutionStatusResponse;
import com.aryan.conduit.workflow.dto.ExecutionSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api/executions")
public class ExecutionController {
    private final ExecutionSummaryService executionSummaryService;
    private final ExecutionService executionService;
    private final ExecutionGraphService executionGraphService;
    private final ExecutionLogStreamService executionLogStreamService;
    private final ExecutionComparisonService executionComparisonService;
    private final com.aryan.conduit.execution.service.ExecutionHistoryService executionHistoryService;
    private final com.aryan.conduit.security.service.WorkflowOwnershipService ownership;

    @GetMapping("/{id}/history")
    public java.util.List<com.aryan.conduit.execution.dto.ExecutionEventResponse> history(@PathVariable Long id,
                                                                                          org.springframework.security.core.Authentication principal) {
        boolean admin = principal.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin && !ownership.ownsExecution(id, principal.getName()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return executionHistoryService.history(id);
    }

    @GetMapping("/{executionId}/summary")
    public ExecutionSummaryResponse summary(@PathVariable Long executionId) {
        return executionSummaryService.getSummary(executionId);
    }

    @GetMapping("/{id}")
    public ExecutionStatusResponse getExecution(@PathVariable Long id){
        return executionService.getExecution(id);
    }

    @GetMapping("/{id}/graph")
    public ExecutionGraphResponse graph(
            @PathVariable Long id
    ) {

        return executionGraphService.getGraph(id);

    }

    @PostMapping("/{id}/replay")
    public Long replay(@PathVariable Long id) {
        return executionService.rerunExecution(id);
    }

    @GetMapping("/{id}/compare/{otherId}")
    public ExecutionComparisonResponse compare(@PathVariable Long id, @PathVariable Long otherId) {
        return executionComparisonService.compare(id, otherId);
    }

    @GetMapping("/{executionId}/stream")
    public SseEmitter streamLogs(@PathVariable Long executionId){
        return executionLogStreamService.subscribe(executionId);
    }
}


