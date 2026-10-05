package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.OutboxEvent;
import com.aryan.conduit.execution.entity.OutboxEventStatus;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventDeliveryService {
    private final OutboxEventRepository outboxEventRepository;
    private final TaskQueueService taskQueueService;
    private final ObjectMapper objectMapper;

    /** Claims and handles one row in its own transaction, releasing the DB lock
     * only after Redis accepted the message and PUBLISHED committed. */
    @Transactional
    public boolean publishNextPending() {
        List<OutboxEvent> rows = outboxEventRepository.lockNextPending();
        if (rows.isEmpty()) return false;

        OutboxEvent event = rows.get(0);
        TaskMessage message;
        try {
            if (!"TASK_QUEUE".equals(event.getEventType())) {
                throw new IllegalArgumentException("Unsupported outbox event type: " + event.getEventType());
            }
            message = objectMapper.readValue(event.getPayload(), TaskMessage.class);
        } catch (JsonProcessingException | IllegalArgumentException poison) {
            event.setStatus(OutboxEventStatus.FAILED);
            event.setLastError(poison.getMessage());
            outboxEventRepository.save(event);
            log.error("Marked malformed outbox event {} as FAILED", event.getId(), poison);
            return true;
        }

        // Redis and PostgreSQL cannot share a transaction. If Redis accepts the
        // append but this DB transaction rolls back, a later delivery can repeat.
        taskQueueService.enqueue(message);
        event.setStatus(OutboxEventStatus.PUBLISHED);
        event.setPublishedAt(LocalDateTime.now());
        event.setLastError(null);
        outboxEventRepository.save(event);
        return true;
    }
}
