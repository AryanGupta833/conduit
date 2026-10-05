package com.aryan.conduit.trigger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class WorkflowTriggerInboxPostgresConcurrencyTest {
    @Autowired WorkflowTriggerInboxRepository inboxRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void concurrentDuplicateEventsReserveOnlyOneInboxRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var reserve = (java.util.concurrent.Callable<Integer>) () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test start timed out");
                }
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return transaction.execute(status -> inboxRepository.reserve(eventId, 73L));
            };
            var first = executor.submit(reserve);
            var second = executor.submit(reserve);
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers did not become ready");
            }
            start.countDown();

            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            WorkflowTriggerInbox row = inboxRepository.findById(eventId).orElseThrow();
            assertEquals(73L, row.getWorkflowId());
            assertNotNull(row.getCreatedAt());
        } finally {
            executor.shutdownNow();
        }
    }
}
