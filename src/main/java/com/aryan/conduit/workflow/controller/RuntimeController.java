package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.service.ExecutionRuntimeService;
import com.aryan.conduit.workflow.dto.RuntimeExecutionContext;
import com.aryan.conduit.workflow.service.RuntimeWorkflowExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/runtime")
public class RuntimeController {
    private final ExecutionRuntimeService executionRuntimeService;
    private final RuntimeWorkflowExecutor runtimeWorkflowExecutor;

    @GetMapping("/test")
    public Boolean test(){
        Map<Long, TaskExecutionStatus> statuses=new HashMap<>();
        statuses.put(15L,TaskExecutionStatus.FAILED);
        return executionRuntimeService.canRun(16L,statuses);
    }

    @GetMapping("/queue/{workflowId}")
    public Object queue(@PathVariable Long workflowId){
        RuntimeExecutionContext context= executionRuntimeService.initializeContext(workflowId);
        return context.getReadyQueue();
    }

    @GetMapping("/simulate/{workflowId}")
    public String simulate(@PathVariable Long workflowId){
        runtimeWorkflowExecutor.simulate(workflowId);
        return "Simulation complete";
    }
}
