package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.IdempotencyRecord;
import com.aryan.conduit.execution.entity.IdempotencyStatus;
import com.aryan.conduit.execution.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LeaseLossProtectionTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Test
    void expiredLeaseCanBeReclaimed() {

        String key =
                "lease-reclaim-test-" + UUID.randomUUID();

        try {

            /*
             * Worker A acquires the lease.
             */
            UUID workerAToken =
                    idempotencyService
                            .tryStart(key)
                            .orElseThrow();

            assertNotNull(workerAToken);

            /*
             * Expire Worker A's lease using the
             * database clock.
             */
            int updated = jdbcTemplate.update("""
                UPDATE idempotency_records
                SET lease_until =
                    CURRENT_TIMESTAMP - INTERVAL '10 seconds'
                WHERE idempotency_key = ?
                """, key);

            assertEquals(1, updated);

            /*
             * Worker B reclaims the expired lease.
             */
            UUID workerBToken =
                    idempotencyService
                            .tryStart(key)
                            .orElseThrow();

            assertNotNull(workerBToken);

            /*
             * Reclaiming the lease must generate
             * a new fencing token.
             */
            assertNotEquals(
                    workerAToken,
                    workerBToken
            );

            /*
             * Verify the record belongs to Worker B.
             */
            IdempotencyRecord record =
                    repository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            assertEquals(
                    IdempotencyStatus.IN_PROGRESS,
                    record.getStatus()
            );

            assertEquals(
                    workerBToken,
                    record.getLeaseToken()
            );

            assertNotNull(
                    record.getLeaseUntil()
            );

            assertTrue(
                    record.getLeaseUntil()
                            .isAfter(LocalDateTime.now())
            );

        } finally {

            repository.findByIdempotencyKey(key)
                    .ifPresent(repository::delete);
        }
    }


    @Test
    void staleWorkerCannotCompleteAfterReclaim() {

        String key =
                "stale-worker-test-" + UUID.randomUUID();

        try {

            /*
             * Worker A acquires the original lease.
             */
            UUID workerAToken =
                    idempotencyService
                            .tryStart(key)
                            .orElseThrow();

            /*
             * Force Worker A's lease to expire.
             */
            int updated = jdbcTemplate.update("""
                UPDATE idempotency_records
                SET lease_until =
                    CURRENT_TIMESTAMP - INTERVAL '10 seconds'
                WHERE idempotency_key = ?
                """, key);

            assertEquals(1, updated);

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
             * Worker A is now stale.
             *
             * It must not be able to complete
             * Worker B's execution.
             */
            boolean staleCompleted =
                    idempotencyService.complete(
                            key,
                            workerAToken,
                            "STALE-WORKER-RESULT"
                    );

            assertFalse(
                    staleCompleted,
                    "Stale worker must not be able to complete a reclaimed lease"
            );

            /*
             * Worker B owns the current lease and
             * should therefore be able to complete.
             */
            boolean workerBCompleted =
                    idempotencyService.complete(
                            key,
                            workerBToken,
                            "WORKER-B-RESULT"
                    );

            assertTrue(workerBCompleted);

            IdempotencyRecord record =
                    repository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            assertEquals(
                    IdempotencyStatus.COMPLETED,
                    record.getStatus()
            );

            assertEquals(
                    "WORKER-B-RESULT",
                    record.getResultJson()
            );

        } finally {

            repository.findByIdempotencyKey(key)
                    .ifPresent(repository::delete);
        }
    }
}