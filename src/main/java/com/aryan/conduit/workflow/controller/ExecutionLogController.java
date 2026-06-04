package com.aryan.conduit.workflow.controller;


import com.aryan.conduit.execution.entity.ExecutionLog;
import com.aryan.conduit.execution.repository.ExecutionLogRepository;
import com.aryan.conduit.workflow.dto.ExecutionLogResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class ExecutionLogController {
    private final ExecutionLogRepository executionLogRepository;

    @GetMapping("/workflow/{workflowExecutionId}")
    public List<ExecutionLogResponse> getLogs(@PathVariable Long workflowExecutionId){
        return executionLogRepository.findByTaskExecution_WorkflowExecution_Id(workflowExecutionId)
                .stream()
                .map(log->new ExecutionLogResponse(log.getTimestamp(),log.getLevel().name(), log.getMessage())).toList();
    }
}
