package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.IdempotencyRecord;
import com.aryan.conduit.execution.entity.IdempotencyStatus;
import com.aryan.conduit.execution.repository.IdempotencyRecordRepository;
import com.aryan.conduit.execution.retry.TaskLeaseHeartbeat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IdempotencyConcurrencyTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Test
    void onlyOneWorkerShouldAcquireNewKey() throws Exception {

        String key =
                "concurrency-test-" + UUID.randomUUID();

        int workerCount = 2;

        ExecutorService executor =
                Executors.newFixedThreadPool(workerCount);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        List<Future<Optional<UUID>>> results =
                new ArrayList<>();

        try {

            for (int i = 0; i < workerCount; i++) {

                results.add(
                        executor.submit(() -> {

                            startLatch.await();

                            return idempotencyService
                                    .tryStart(key);
                        })
                );
            }

            /*
             * Release both workers at approximately
             * the same time.
             */
            startLatch.countDown();

            int successfulAcquisitions = 0;

            for (Future<Optional<UUID>> result : results) {

                if (result.get().isPresent()) {
                    successfulAcquisitions++;
                }
            }

            assertEquals(
                    1,
                    successfulAcquisitions,
                    "Exactly one worker should acquire the idempotency key"
            );

        } finally {

            executor.shutdownNow();

            repository.findByIdempotencyKey(key)
                    .ifPresent(repository::delete);
        }
    }


    @Test
    void expiredLeaseShouldBeReclaimed() {

        String key =
                "expired-lease-test-" + UUID.randomUUID();

        try {

            /*
             * First worker acquires the key.
             */
            UUID firstToken =
                    idempotencyService
                            .tryStart(key)
                            .orElseThrow();

            assertNotNull(firstToken);

            /*
             * Force the lease into the past so that we
             * can test recovery without waiting 60 seconds.
             */
            int updated = jdbcTemplate.update("""
                UPDATE idempotency_records
                SET lease_until =
                    CURRENT_TIMESTAMP - INTERVAL '10 seconds'
                WHERE idempotency_key = ?
                """, key);

            assertEquals(1, updated);

            /*
             * A second worker should now be able to
             * reclaim the expired lease.
             */
            UUID secondToken =
                    idempotencyService
                            .tryStart(key)
                            .orElseThrow();

            assertNotNull(secondToken);

            /*
             * Reclamation must generate a fresh fencing token.
             */
            assertNotEquals(
                    firstToken,
                    secondToken
            );

            /*
             * Verify that the record is once again
             * IN_PROGRESS and has a fresh lease.
             */
            IdempotencyRecord updatedRecord =
                    repository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            assertEquals(
                    IdempotencyStatus.IN_PROGRESS,
                    updatedRecord.getStatus()
            );

            assertNotNull(
                    updatedRecord.getLeaseUntil()
            );

            assertTrue(
                    updatedRecord
                            .getLeaseUntil()
                            .isAfter(LocalDateTime.now())
            );

            assertEquals(
                    secondToken,
                    updatedRecord.getLeaseToken()
            );

        } finally {

            repository.findByIdempotencyKey(key)
                    .ifPresent(repository::delete);
        }
    }


    @Test
    void onlyOneWorkerShouldReclaimExpiredLease() throws Exception {

        String key =
                "expired-concurrency-test-" + UUID.randomUUID();

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch startLatch =
                new CountDownLatch(1);

        List<Future<Optional<UUID>>> results =
                new ArrayList<>();

        try {

            /*
             * Initial acquisition.
             */
            UUID originalToken =
                    idempotencyService
                            .tryStart(key)
                            .orElseThrow();

            assertNotNull(originalToken);

            /*
             * Force the lease to expire.
             */
            int updated = jdbcTemplate.update("""
                UPDATE idempotency_records
                SET lease_until =
                    CURRENT_TIMESTAMP - INTERVAL '10 seconds'
                WHERE idempotency_key = ?
                """, key);

            assertEquals(1, updated);

            /*
             * Both workers attempt to reclaim the
             * same expired lease simultaneously.
             */
            for (int i = 0; i < 2; i++) {

                results.add(
                        executor.submit(() -> {

                            startLatch.await();

                            return idempotencyService
                                    .tryStart(key);
                        })
                );
            }

            startLatch.countDown();

            int successfulReclaims = 0;
            UUID reclaimedToken = null;

            for (Future<Optional<UUID>> result : results) {

                Optional<UUID> token =
                        result.get();

                if (token.isPresent()) {

                    successfulReclaims++;
                    reclaimedToken = token.get();
                }
            }

            /*
             * Only one UPDATE should satisfy:
             *
             * lease_until < CURRENT_TIMESTAMP
             */
            assertEquals(
                    1,
                    successfulReclaims,
                    "Exactly one worker should reclaim the expired lease"
            );

            assertNotNull(reclaimedToken);

            assertNotEquals(
                    originalToken,
                    reclaimedToken
            );

            IdempotencyRecord record =
                    repository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            assertEquals(
                    IdempotencyStatus.IN_PROGRESS,
                    record.getStatus()
            );

            assertEquals(
                    reclaimedToken,
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

            executor.shutdownNow();

            repository.findByIdempotencyKey(key)
                    .ifPresent(repository::delete);
        }
    }


    @Test
    void validLeaseShouldBeRenewed() {

        String key =
                "renew-test-" + UUID.randomUUID();

        try {

            UUID leaseToken =
                    idempotencyService
                            .tryStart(key)
                            .orElseThrow();

            IdempotencyRecord before =
                    repository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            LocalDateTime oldLease =
                    before.getLeaseUntil();

            assertNotNull(oldLease);

            /*
             * Small delay so the new DB timestamp is
             * guaranteed to move forward.
             */
            Thread.sleep(100);

            boolean renewed =
                    idempotencyService.renewLease(
                            key,
                            leaseToken
                    );

            assertTrue(renewed);

            IdempotencyRecord after =
                    repository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            assertNotNull(
                    after.getLeaseUntil()
            );

            assertTrue(
                    after.getLeaseUntil()
                            .isAfter(oldLease)
            );

            assertEquals(
                    leaseToken,
                    after.getLeaseToken()
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            fail("Test interrupted");

        } finally {

            repository.findByIdempotencyKey(key)
                    .ifPresent(repository::delete);
        }
    }


    @Test
    void expiredLeaseShouldNotBeRenewed() {

        String key =
                "expired-renew-test-" + UUID.randomUUID();

        try {

            UUID leaseToken =
                    idempotencyService
                            .tryStart(key)
                            .orElseThrow();

            /*
             * Expire the lease using DB time rather
             * than application time.
             */
            int updated = jdbcTemplate.update("""
                UPDATE idempotency_records
                SET lease_until =
                    CURRENT_TIMESTAMP - INTERVAL '10 seconds'
                WHERE idempotency_key = ?
                """, key);

            assertEquals(1, updated);

            boolean renewed =
                    idempotencyService.renewLease(
                            key,
                            leaseToken
                    );

            assertFalse(renewed);

            IdempotencyRecord unchanged =
                    repository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            assertTrue(
                    unchanged.getLeaseUntil()
                            .isBefore(LocalDateTime.now())
            );

        } finally {

            repository.findByIdempotencyKey(key)
                    .ifPresent(repository::delete);
        }
    }


    @Test
    void heartbeatShouldRenewLease() throws Exception {

        String key =
                "heartbeat-test-" + UUID.randomUUID();

        TaskLeaseHeartbeat heartbeat = null;

        try {

            UUID leaseToken =
                    idempotencyService
                            .tryStart(key)
                            .orElseThrow();

            IdempotencyRecord before =
                    repository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            LocalDateTime initialLease =
                    before.getLeaseUntil();

            assertNotNull(initialLease);

            heartbeat =
                    new TaskLeaseHeartbeat(
                            idempotencyService,
                            key,
                            leaseToken
                    );

            heartbeat.start();

            /*
             * Heartbeat runs every 20 seconds.
             */
            Thread.sleep(22000);

            assertFalse(
                    heartbeat.isLeaseLost(),
                    "Heartbeat should not lose a valid lease"
            );

            IdempotencyRecord after =
                    repository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            assertTrue(
                    after.getLeaseUntil()
                            .isAfter(initialLease)
            );

            assertEquals(
                    leaseToken,
                    after.getLeaseToken()
            );

        } finally {

            if (heartbeat != null) {
                heartbeat.stop();
            }

            repository.findByIdempotencyKey(key)
                    .ifPresent(repository::delete);
        }
    }


    @Test
    void staleWorkerCannotCompleteAfterLeaseReclaimed() {

        String key =
                "lease-loss-test-" + UUID.randomUUID();

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

            /*
             * Reclamation must generate a different
             * fencing token.
             */
            assertNotEquals(
                    workerAToken,
                    workerBToken
            );

            /*
             * Worker A is stale now.
             *
             * It must NOT be able to complete the task.
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
             * therefore should be able to complete.
             */
            boolean workerBCompleted =
                    idempotencyService.complete(
                            key,
                            workerBToken,
                            "WORKER-B-RESULT"
                    );

            assertTrue(workerBCompleted);

            IdempotencyRecord finalRecord =
                    repository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            assertEquals(
                    IdempotencyStatus.COMPLETED,
                    finalRecord.getStatus()
            );

            assertEquals(
                    "WORKER-B-RESULT",
                    finalRecord.getResultJson()
            );

        } finally {

            repository.findByIdempotencyKey(key)
                    .ifPresent(repository::delete);
        }
    }


    @Test
    void staleWorkerCannotRenewAfterLeaseReclaimed() {

        String key =
                "stale-renew-test-" + UUID.randomUUID();

        try {

            /*
             * Worker A gets the original lease.
             */
            UUID workerAToken =
                    idempotencyService
                            .tryStart(key)
                            .orElseThrow();

            /*
             * Expire Worker A's lease.
             */
            int updated = jdbcTemplate.update("""
                UPDATE idempotency_records
                SET lease_until =
                    CURRENT_TIMESTAMP - INTERVAL '10 seconds'
                WHERE idempotency_key = ?
                """, key);

            assertEquals(1, updated);

            /*
             * Worker B reclaims it.
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
             * Stale Worker A must not be able
             * to renew Worker B's lease.
             */
            boolean staleRenewed =
                    idempotencyService.renewLease(
                            key,
                            workerAToken
                    );

            assertFalse(
                    staleRenewed,
                    "Stale worker must not renew another worker's lease"
            );

            /*
             * Worker B should still be able to
             * renew its own lease.
             */
            boolean workerBRenewed =
                    idempotencyService.renewLease(
                            key,
                            workerBToken
                    );

            assertTrue(workerBRenewed);

        } finally {

            repository.findByIdempotencyKey(key)
                    .ifPresent(repository::delete);
        }
    }


    @Test
    void staleWorkerCannotReleaseAfterLeaseReclaimed() {

        String key =
                "stale-release-test-" + UUID.randomUUID();

        try {

            /*
             * Worker A gets the original lease.
             */
            UUID workerAToken =
                    idempotencyService
                            .tryStart(key)
                            .orElseThrow();

            /*
             * Expire Worker A's lease.
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
             * Worker A is stale and must not be
             * able to release Worker B's lease.
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

            /*
             * Worker B can legitimately release its lease.
             */
            idempotencyService.release(
                    key,
                    workerBToken
            );

            IdempotencyRecord finalRecord =
                    repository
                            .findByIdempotencyKey(key)
                            .orElseThrow();

            assertEquals(
                    IdempotencyStatus.FAILED,
                    finalRecord.getStatus()
            );

        } finally {

            repository.findByIdempotencyKey(key)
                    .ifPresent(repository::delete);
        }
    }
}