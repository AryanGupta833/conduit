package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.service.*;
import com.aryan.conduit.workflow.dto.ExecutionGraphResponse;
import com.aryan.conduit.workflow.dto.ExecutionResponse;
import com.aryan.conduit.workflow.dto.ExecutionStatusResponse;
import com.aryan.conduit.workflow.dto.ExecutionSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
    private final TaskRunnerService taskRunnerService;

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

    @PostMapping("/test/idempotency/{taskExecutionId}")
    public ResponseEntity<String> testIdempotency(
            @PathVariable Long taskExecutionId) throws InterruptedException {

        taskRunnerService.executeWithRetry(
                taskExecutionId,
                2,
                10
        );

        return ResponseEntity.ok(
                "Task execution completed: " + taskExecutionId
        );
    }

}


