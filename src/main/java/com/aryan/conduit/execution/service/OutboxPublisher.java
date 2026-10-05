package com.aryan.conduit.execution.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private static final int MAX_EVENTS_PER_POLL = 100;
    private final OutboxEventDeliveryService deliveryService;

    @Scheduled(fixedDelayString = "${conduit.outbox.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        for (int i = 0; i < MAX_EVENTS_PER_POLL; i++) {
            try {
                if (!deliveryService.publishNextPending()) return;
            } catch (RuntimeException failure) {
                // The transaction rolls back and releases the row lock. A later
                // poll retries the event; do not prevent unrelated schedulers.
                log.warn("Outbox delivery failed; pending event will be retried", failure);
                return;
            }
        }
    }
}
