package com.aryan.conduit.execution.service;


import com.aryan.conduit.workflow.dto.ExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final ExecutionCreationService executionCreationService;
    private final TaskRunnerService taskRunnerService;

    public Long startWorkflow(Long workflowId){

        ExecutionContext context =
                executionCreationService
                        .createExecution(workflowId);

        taskRunnerService.runExecution(
                context.workflowExecution(),
                context.stages(),
                context.taskExecutionMap()
        );

        return context.workflowExecution().getId();
    }
}