package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.service.ExecutionSummaryService;
import com.aryan.conduit.workflow.dto.ExecutionSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequiredArgsConstructor
public class ExecutionController {
    private final ExecutionSummaryService executionSummaryService;

    @GetMapping("/{executionId}/summary")
    public ExecutionSummaryResponse summary(@PathVariable Long executionId){
        return executionSummaryService.getSummary(executionId);
    }


}


