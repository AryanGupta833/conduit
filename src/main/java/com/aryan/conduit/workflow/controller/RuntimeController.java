package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.service.ExecutionCreationService;
import com.aryan.conduit.execution.service.ExecutionRuntimeService;
import com.aryan.conduit.execution.service.ExecutionService;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import com.aryan.conduit.workflow.dto.RuntimeExecutionContext;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
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
    private final ExecutionService executionService;
    private final ExecutionCreationService executionCreationService;
    private final WorkflowVersionRepository workflowVersionRepository;

    @GetMapping("/test")
    public Boolean test(){
        Map<Long, TaskExecutionStatus> statuses=new HashMap<>();
        statuses.put(15L,TaskExecutionStatus.FAILED);
        return executionRuntimeService.canRun(469L,16L,statuses);
    }

    @GetMapping("/queue/{workflowId}")
    public Object queue(@PathVariable Long workflowId){
        WorkflowVersion version=workflowVersionRepository.findByWorkflow_IdAndLatestTrue(workflowId).orElseThrow(()->new IllegalStateException("No published version found"));
        RuntimeExecutionContext context= executionRuntimeService.initializeContext(version.getId());
        return context.getReadyQueue();
    }

    @GetMapping("/simulate/{workflowId}")
    public String simulate(@PathVariable Long workflowId){
        WorkflowVersion version=workflowVersionRepository.findByWorkflow_IdAndLatestTrue(workflowId).orElseThrow(()->new IllegalStateException("No published version found"));
        runtimeWorkflowExecutor.simulate(version.getId());
        return "Simulation complete";
    }

    @GetMapping("/execute/{workflowId}")
    public String execute(@PathVariable Long workflowId){
        try{
            Long executionId=executionService.startWorkflow(workflowId);
            return "Workflow Started,Id= "+executionId;
        }
        catch (IllegalStateException e){
            return e.getMessage();
        }
    }
}
