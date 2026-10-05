package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.OutboxEvent;
import com.aryan.conduit.execution.entity.OutboxEventStatus;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxEventDeliveryServiceTest {
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final TaskQueueService queue = mock(TaskQueueService.class);
    private final OutboxEventDeliveryService service = new OutboxEventDeliveryService(repository, queue, new ObjectMapper());

    @Test
    void publishesClaimedEventAndMarksItPublished() {
        OutboxEvent event = event("TASK_QUEUE", "{\"workflowExecutionId\":1,\"taskExecutionId\":2,\"taskNodeId\":3}");
        when(repository.lockNextPending()).thenReturn(List.of(event));

        assertTrue(service.publishNextPending());

        verify(queue).enqueue(new TaskMessage(1L, 2L, 3L));
        assertEquals(OutboxEventStatus.PUBLISHED, event.getStatus());
        assertNotNull(event.getPublishedAt());
        verify(repository).save(event);
    }

    @Test
    void marksMalformedPoisonEventFailedSoItCannotBlockLaterEvents() {
        OutboxEvent event = event("TASK_QUEUE", "not-json");
        when(repository.lockNextPending()).thenReturn(List.of(event));

        assertTrue(service.publishNextPending());

        assertEquals(OutboxEventStatus.FAILED, event.getStatus());
        assertNotNull(event.getLastError());
        verifyNoInteractions(queue);
        verify(repository).save(event);
    }

    private OutboxEvent event(String type, String payload) {
        return OutboxEvent.builder().id(1L).eventType(type).aggregateId(2L)
                .payload(payload).status(OutboxEventStatus.PENDING).createdAt(LocalDateTime.now()).build();
    }
}
