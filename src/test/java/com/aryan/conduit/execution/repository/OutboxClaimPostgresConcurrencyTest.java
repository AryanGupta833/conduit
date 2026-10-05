package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.OutboxEvent;
import com.aryan.conduit.execution.entity.OutboxEventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class OutboxClaimPostgresConcurrencyTest {
    @Autowired OutboxEventRepository repository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void concurrentPublishersLockDifferentPendingRows() throws Exception {
        repository.deleteAll();
        var first = repository.saveAndFlush(event("one"));
        var second = repository.saveAndFlush(event("two"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstClaim = executor.submit(() -> {
                TransactionTemplate tx = requiresNew();
                return tx.execute(status -> {
                    Long id = repository.lockNextPending().getFirst().getId();
                    locked.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return id;
                });
            });
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var secondClaim = executor.submit(() -> requiresNew().execute(status -> repository.lockNextPending().getFirst().getId()));
            Long secondClaimedId = secondClaim.get(5, TimeUnit.SECONDS);
            release.countDown();
            Long firstClaimedId = firstClaim.get(5, TimeUnit.SECONDS);

            assertNotEquals(firstClaimedId, secondClaimedId);
            assertTrue(java.util.List.of(first.getId(), second.getId()).containsAll(java.util.List.of(firstClaimedId, secondClaimedId)));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private OutboxEvent event(String payload) {
        return OutboxEvent.builder().eventType("TASK_QUEUE").aggregateId(1L).payload(payload)
                .status(OutboxEventStatus.PENDING).createdAt(LocalDateTime.now()).build();
    }
}
