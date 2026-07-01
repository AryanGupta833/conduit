package com.aryan.conduit.execution.service;


import com.aryan.conduit.workflow.dto.ExecutionContext;
import com.aryan.conduit.workflow.service.RuntimeWorkflowExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final ExecutionCreationService executionCreationService;
    private final TaskRunnerService taskRunnerService;
    private final RuntimeWorkflowExecutor runtimeWorkflowExecutor;

    @Async("workflowExecutor")
    public void startWorkflowAsync(Long workflowId){

        ExecutionContext context =
                executionCreationService
                        .createExecution(workflowId);

        runtimeWorkflowExecutor.execute(context.workflowExecution().getId(),workflowId);

    }
    public Long startWorkflow(Long workflowId){

        ExecutionContext context =
                executionCreationService.createExecution(workflowId);

        runtimeWorkflowExecutor.execute(
                context.workflowExecution().getId(),
                workflowId
        );

        return context.workflowExecution().getId();
    }
}