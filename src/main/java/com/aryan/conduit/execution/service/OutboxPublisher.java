package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.OutboxEvent;
import com.aryan.conduit.execution.entity.OutboxEventStatus;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final TaskQueueService taskQueueService;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {

        List<OutboxEvent> events =
                outboxEventRepository
                        .findTop100ByStatusOrderByIdAsc(
                                OutboxEventStatus.PENDING
                        );

        for (OutboxEvent event : events) {
            publish(event);
        }
    }

    @Transactional
    public void publish(
            OutboxEvent event
    ) {

        if (event.getStatus()
                != OutboxEventStatus.PENDING) {
            return;
        }

        TaskMessage taskMessage;

        try {

            taskMessage =
                    objectMapper.readValue(
                            event.getPayload(),
                            TaskMessage.class
                    );

        } catch (JsonProcessingException e) {

            throw new IllegalStateException(
                    "Failed to deserialize outbox event "
                            + event.getId(),
                    e
            );
        }

        taskQueueService.enqueue(taskMessage);

        event.setStatus(
                OutboxEventStatus.PUBLISHED
        );

        event.setPublishedAt(
                LocalDateTime.now()
        );

        outboxEventRepository.save(event);
    }
}