package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.service.ExecutionGraphService;
import com.aryan.conduit.execution.service.ExecutionLogStreamService;
import com.aryan.conduit.execution.service.ExecutionService;
import com.aryan.conduit.execution.service.ExecutionSummaryService;
import com.aryan.conduit.workflow.dto.ExecutionGraphResponse;
import com.aryan.conduit.workflow.dto.ExecutionResponse;
import com.aryan.conduit.workflow.dto.ExecutionStatusResponse;
import com.aryan.conduit.workflow.dto.ExecutionSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api/executions")
public class ExecutionController {
    private final ExecutionSummaryService executionSummaryService;
    private final ExecutionService executionService;
    private final ExecutionGraphService executionGraphService;
    private final ExecutionLogStreamService executionLogStreamService;

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

    @GetMapping("/{executionId}/stream")
    public SseEmitter streamLogs(@PathVariable Long executionId){
        return executionLogStreamService.subscribe(executionId);
    }


}


