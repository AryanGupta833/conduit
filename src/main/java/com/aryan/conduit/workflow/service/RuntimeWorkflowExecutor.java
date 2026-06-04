package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.service.ExecutionRuntimeService;
import com.aryan.conduit.workflow.dto.RuntimeExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowExecutor {
    private final ExecutionRuntimeService executionRuntimeService;

    public void simulate(Long workflowId){
        RuntimeExecutionContext context= executionRuntimeService.initializeContext(workflowId);

        while (!context.getReadyQueue().isEmpty()){
            Long taskId=context.getReadyQueue().poll();
            System.out.println("Executing "+taskId);
            context.getTaskStatuses().put(taskId, TaskExecutionStatus.SUCCESS);
            executionRuntimeService.evaluateChildren(taskId,context);
        }
    }
}
