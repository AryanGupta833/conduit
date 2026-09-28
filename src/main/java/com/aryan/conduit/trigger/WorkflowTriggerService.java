package com.aryan.conduit.trigger;

import com.aryan.conduit.execution.service.ExecutionCreationService;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import com.aryan.conduit.workflow.service.WorkflowExecutionAsyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkflowTriggerService {

    private final ExecutionCreationService executionCreationService;
    private final WorkflowExecutionAsyncService workflowExecutionAsyncService;

    public Long triggerWorkflow(Long workflowId) {

        ExecutionContext executionContext =
                executionCreationService.createExecution(
                        workflowId
                );

        Long workflowExecutionId =
                executionContext.workflowExecution().getId();

        workflowExecutionAsyncService.executeAsync(
                workflowExecutionId
        );

        return workflowExecutionId;
    }
}