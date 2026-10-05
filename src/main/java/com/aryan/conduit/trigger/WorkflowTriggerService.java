package com.aryan.conduit.trigger;

import com.aryan.conduit.workflow.service.WorkflowExecutionAsyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkflowTriggerService {

    private final WorkflowTriggerInboxService triggerInboxService;
    private final WorkflowExecutionAsyncService workflowExecutionAsyncService;

    public Long triggerWorkflow(Long workflowId) {
        return triggerWorkflow(new WorkflowTriggerEvent(workflowId));
    }

    public Long triggerWorkflow(WorkflowTriggerEvent event) {
        Long workflowExecutionId = triggerInboxService.getOrCreateExecution(
                event.eventId(), event.workflowId());

        workflowExecutionAsyncService.executeAsync(
                workflowExecutionId
        );

        return workflowExecutionId;
    }
}
