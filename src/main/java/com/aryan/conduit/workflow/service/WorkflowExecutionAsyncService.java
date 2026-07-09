package com.aryan.conduit.workflow.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkflowExecutionAsyncService {

    private final RuntimeWorkflowExecutor runtimeWorkflowExecutor;

    @Async("workflowExecutor")
    public void executeAsync(Long workflowExecutionId){
        runtimeWorkflowExecutor.execute(workflowExecutionId);
    }
}
