package com.aryan.conduit.trigger;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkflowTriggerProducer {

    private static final String TOPIC =
            "conduit.workflow.trigger";

    private final KafkaTemplate<String, WorkflowTriggerEvent>
            kafkaTemplate;

    public void trigger(Long workflowId) {

        WorkflowTriggerEvent event =
                new WorkflowTriggerEvent(workflowId);

        kafkaTemplate.send(
                TOPIC,
                String.valueOf(workflowId),
                event
        );
    }
}