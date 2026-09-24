package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.aryan.conduit.execution.entity.IdempotencyRecord;
import com.aryan.conduit.execution.entity.IdempotencyStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class IdempotencyConcurrencyTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRecordRepository repository;

    @Test
    void onlyOneWorkerShouldAcquireNewKey() throws Exception {

        String key = "concurrency-test-" + UUID.randomUUID();

        int workerCount = 2;

        ExecutorService executor =
                Executors.newFixedThreadPool(workerCount);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        List<Future<Boolean>> results =
                new ArrayList<>();

        for (int i = 0; i < workerCount; i++) {

            results.add(
                    executor.submit(() -> {

                        startLatch.await();

                        return idempotencyService.tryStart(key);
                    })
            );
        }

        /*
         * Release both workers at approximately the
         * same time.
         */
        startLatch.countDown();

        int successfulAcquisitions = 0;

        for (Future<Boolean> result : results) {

            if (result.get()) {
                successfulAcquisitions++;
            }
        }

        executor.shutdown();

        assertEquals(
                1,
                successfulAcquisitions,
                "Exactly one worker should acquire the idempotency key"
        );

        repository.findByIdempotencyKey(key)
                .ifPresent(repository::delete);
    }

    @Test
    void expiredLeaseShouldBeReclaimed() {

        String key = "expired-lease-test-" + UUID.randomUUID();

        /*
         * First worker acquires the key.
         */
        boolean firstAcquisition =
                idempotencyService.tryStart(key);

        assertEquals(true, firstAcquisition);

        /*
         * Force the lease into the past so that we can
         * test recovery without waiting 60 seconds.
         */
        IdempotencyRecord record =
                repository.findByIdempotencyKey(key)
                        .orElseThrow();

        record.setLeaseUntil(
                LocalDateTime.now().minusSeconds(10)
        );

        repository.saveAndFlush(record);

        /*
         * A second worker should now be able to reclaim
         * the expired lease.
         */
        boolean reclaimed =
                idempotencyService.tryStart(key);

        assertEquals(true, reclaimed);

        /*
         * Verify that the record is once again IN_PROGRESS
         * and has a fresh lease.
         */
        IdempotencyRecord updated =
                repository.findByIdempotencyKey(key)
                        .orElseThrow();

        assertEquals(
                IdempotencyStatus.IN_PROGRESS,
                updated.getStatus()
        );

        assertTrue(
                updated.getLeaseUntil()
                        .isAfter(LocalDateTime.now())
        );

        repository.delete(updated);
    }
    @Test
    void onlyOneWorkerShouldReclaimExpiredLease() throws Exception {

        String key = "expired-concurrency-test-" + UUID.randomUUID();

        // Initial acquisition
        boolean acquired =
                idempotencyService.tryStart(key);

        assertEquals(true, acquired);

        // Force lease to expire
        IdempotencyRecord record =
                repository.findByIdempotencyKey(key)
                        .orElseThrow();

        record.setLeaseUntil(
                LocalDateTime.now().minusSeconds(10)
        );

        repository.saveAndFlush(record);

        int workerCount = 2;

        ExecutorService executor =
                Executors.newFixedThreadPool(workerCount);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        List<Future<Boolean>> results =
                new ArrayList<>();

        for (int i = 0; i < workerCount; i++) {

            results.add(
                    executor.submit(() -> {

                        startLatch.await();

                        return idempotencyService.tryStart(key);
                    })
            );
        }

        // Release both workers simultaneously
        startLatch.countDown();

        int successfulReclaims = 0;

        for (Future<Boolean> result : results) {

            if (result.get()) {
                successfulReclaims++;
            }
        }

        executor.shutdown();

        assertEquals(
                1,
                successfulReclaims,
                "Exactly one worker should reclaim the expired lease"
        );

        IdempotencyRecord updated =
                repository.findByIdempotencyKey(key)
                        .orElseThrow();

        assertEquals(
                IdempotencyStatus.IN_PROGRESS,
                updated.getStatus()
        );

        assertTrue(
                updated.getLeaseUntil()
                        .isAfter(LocalDateTime.now())
        );

        repository.delete(updated);
    }
}