package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.OutboxEvent;
import com.aryan.conduit.execution.entity.OutboxEventStatus;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private static final String TASK_QUEUE_EVENT =
            "TASK_QUEUE";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void createTaskQueueEvent(
            TaskMessage taskMessage
    ) {

        String payload;

        try {

            payload =
                    objectMapper.writeValueAsString(
                            taskMessage
                    );

        } catch (JsonProcessingException e) {

            throw new IllegalStateException(
                    "Failed to serialize task message",
                    e
            );
        }

        OutboxEvent event =
                OutboxEvent.builder()
                        .eventType(TASK_QUEUE_EVENT)
                        .aggregateId(
                                taskMessage.taskExecutionId()
                        )
                        .payload(payload)
                        .status(
                                OutboxEventStatus.PENDING
                        )
                        .createdAt(
                                LocalDateTime.now()
                        )
                        .build();

        outboxEventRepository.save(event);
    }
}