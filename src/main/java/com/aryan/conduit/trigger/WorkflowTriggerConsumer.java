package com.aryan.conduit.trigger;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkflowTriggerConsumer {

    private static final String TOPIC =
            "conduit.workflow.trigger";

    private final WorkflowTriggerService workflowTriggerService;

    @KafkaListener(
            topics = TOPIC,
            groupId = "conduit-workflow-triggers"
    )
    public void consume(
            WorkflowTriggerEvent event
    ) {

        if (event == null || event.workflowId() == null) {
            throw new IllegalArgumentException(
                    "Workflow trigger must contain workflowId"
            );
        }

        Long executionId =
                workflowTriggerService.triggerWorkflow(
                        event.workflowId()
                );

        System.out.println(
                "Workflow triggered successfully. "
                        + "workflowId="
                        + event.workflowId()
                        + ", executionId="
                        + executionId
        );
    }
}