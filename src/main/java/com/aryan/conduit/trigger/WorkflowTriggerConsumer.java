package com.aryan.conduit.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
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

        log.info("Workflow {} triggered as execution {}", event.workflowId(), executionId);
    }
}
