package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.IdempotencyRecord;
import com.aryan.conduit.execution.entity.IdempotencyStatus;
import com.aryan.conduit.execution.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IdempotencyPostgresIntegrationTest {

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void shouldAcquireLeaseForNewKey() {

        String key =
                "integration-test:new-key";

        Optional<UUID> token =
                idempotencyService.tryStart(key);

        assertTrue(token.isPresent());

        IdempotencyRecord record =
                repository
                        .findByIdempotencyKey(key)
                        .orElseThrow();

        assertEquals(
                IdempotencyStatus.IN_PROGRESS,
                record.getStatus()
        );

        assertNotNull(
                record.getLeaseToken()
        );

        assertEquals(
                token.get(),
                record.getLeaseToken()
        );
    }

    @Test
    void shouldRejectStaleWorkerAfterLeaseReclamation() {

        String key =
                "integration-test:fencing";

        /*
         * Worker A acquires the original lease.
         */
        UUID workerAToken =
                idempotencyService
                        .tryStart(key)
                        .orElseThrow();

        IdempotencyRecord workerARecord =
                repository
                        .findByIdempotencyKey(key)
                        .orElseThrow();

        /*
         * Simulate Worker A's lease expiring.
         *
         * We deliberately move the lease into the past
         * instead of waiting 60 seconds.
         */
        workerARecord.setLeaseUntil(
                LocalDateTime.now().minusSeconds(10)
        );

        repository.saveAndFlush(
                workerARecord
        );

        /*
         * Worker B attempts to acquire the same task.
         */
        UUID workerBToken =
                idempotencyService
                        .tryStart(key)
                        .orElseThrow();

        assertNotEquals(
                workerAToken,
                workerBToken
        );

        /*
         * Worker A is now stale.
         *
         * Its old fencing token must NOT be able
         * to commit the result.
         */
        boolean staleWorkerCompleted =
                idempotencyService.complete(
                        key,
                        workerAToken,
                        "{\"success\":true,\"output\":\"stale\"}"
                );

        assertFalse(
                staleWorkerCompleted
        );

        /*
         * Worker B owns the current lease and
         * therefore must be able to commit.
         */
        boolean currentWorkerCompleted =
                idempotencyService.complete(
                        key,
                        workerBToken,
                        "{\"success\":true,\"output\":\"current\"}"
                );

        assertTrue(
                currentWorkerCompleted
        );

        /*
         * Verify the database contains Worker B's
         * result and COMPLETED state.
         */
        IdempotencyRecord finalRecord =
                repository
                        .findByIdempotencyKey(key)
                        .orElseThrow();

        assertEquals(
                IdempotencyStatus.COMPLETED,
                finalRecord.getStatus()
        );

        assertEquals(
                "{\"success\":true,\"output\":\"current\"}",
                finalRecord.getResultJson()
        );

        assertEquals(
                workerBToken,
                finalRecord.getLeaseToken()
        );
    }

    @Test
    void shouldRejectStaleWorkerRenewal() {

        String key =
                "integration-test:renew-fencing";

        UUID workerAToken =
                idempotencyService
                        .tryStart(key)
                        .orElseThrow();

        IdempotencyRecord record =
                repository
                        .findByIdempotencyKey(key)
                        .orElseThrow();

        /*
         * Expire Worker A's lease.
         */
        record.setLeaseUntil(
                LocalDateTime.now().minusSeconds(10)
        );

        repository.saveAndFlush(record);

        /*
         * Worker B reclaims the lease.
         */
        UUID workerBToken =
                idempotencyService
                        .tryStart(key)
                        .orElseThrow();

        assertNotEquals(
                workerAToken,
                workerBToken
        );

        /*
         * Worker A must not be able to renew
         * Worker B's lease.
         */
        boolean staleRenewal =
                idempotencyService.renewLease(
                        key,
                        workerAToken
                );

        assertFalse(
                staleRenewal
        );

        /*
         * Worker B must still be able to renew.
         */
        boolean currentRenewal =
                idempotencyService.renewLease(
                        key,
                        workerBToken
                );

        assertTrue(
                currentRenewal
        );
    }

    @Test
    void shouldRejectStaleWorkerRelease() {

        String key =
                "integration-test:release-fencing";

        UUID workerAToken =
                idempotencyService
                        .tryStart(key)
                        .orElseThrow();

        IdempotencyRecord record =
                repository
                        .findByIdempotencyKey(key)
                        .orElseThrow();

        /*
         * Expire Worker A's lease.
         */
        record.setLeaseUntil(
                LocalDateTime.now().minusSeconds(10)
        );

        repository.saveAndFlush(record);

        /*
         * Worker B takes ownership.
         */
        UUID workerBToken =
                idempotencyService
                        .tryStart(key)
                        .orElseThrow();

        assertNotEquals(
                workerAToken,
                workerBToken
        );

        /*
         * Worker A tries to release the task.
         * This must not affect Worker B.
         */
        idempotencyService.release(
                key,
                workerAToken
        );

        IdempotencyRecord afterStaleRelease =
                repository
                        .findByIdempotencyKey(key)
                        .orElseThrow();

        assertEquals(
                IdempotencyStatus.IN_PROGRESS,
                afterStaleRelease.getStatus()
        );

        assertEquals(
                workerBToken,
                afterStaleRelease.getLeaseToken()
        );
    }
}