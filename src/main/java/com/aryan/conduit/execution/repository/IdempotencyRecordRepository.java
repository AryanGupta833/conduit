package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.IdempotencyRecord;
import com.aryan.conduit.execution.entity.IdempotencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository
        extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Query("""
        UPDATE IdempotencyRecord r
        SET r.status = :newStatus,
            r.resultJson = NULL,
            r.completedAt = NULL
        WHERE r.idempotencyKey = :key
          AND r.status = :expectedStatus
    """)
    int updateStatusIf(
            @Param("key") String key,
            @Param("expectedStatus") IdempotencyStatus expectedStatus,
            @Param("newStatus") IdempotencyStatus newStatus
    );


    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
    INSERT INTO idempotency_records
        (idempotency_key, status, created_at, lease_until, lease_token)
    VALUES
        (:key, 'IN_PROGRESS', CURRENT_TIMESTAMP,
         CURRENT_TIMESTAMP + INTERVAL '60 seconds',
         gen_random_uuid())
    ON CONFLICT (idempotency_key) DO NOTHING
    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("key") String key);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
    UPDATE idempotency_records
    SET status = 'IN_PROGRESS',
        result_json = NULL,
        completed_at = NULL,
        lease_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds',
        lease_token = gen_random_uuid()
    WHERE idempotency_key = :key
      AND status = 'IN_PROGRESS'
      AND lease_until < CURRENT_TIMESTAMP
    """, nativeQuery = true)
    int reclaimExpiredLease(
            @Param("key") String key);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
    UPDATE idempotency_records
    SET status = 'IN_PROGRESS',
        result_json = NULL,
        completed_at = NULL,
        lease_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds',
        lease_token = gen_random_uuid()
    WHERE idempotency_key = :key
      AND status = 'FAILED'
    """, nativeQuery = true)
    int reacquireFailed(
            @Param("key") String key);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
    UPDATE idempotency_records
    SET lease_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds'
    WHERE idempotency_key = :key
      AND lease_token = :token
      AND status = 'IN_PROGRESS'
      AND lease_until > CURRENT_TIMESTAMP
    """, nativeQuery = true)
    int renewLease(
            @Param("key") String key,
            @Param("token") UUID token);


    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
    UPDATE idempotency_records
    SET status = 'COMPLETED',
        result_json = :resultJson,
        completed_at = CURRENT_TIMESTAMP,
        lease_until = NULL
    WHERE idempotency_key = :key
      AND lease_token = :token
      AND status = 'IN_PROGRESS'
      AND lease_until > CURRENT_TIMESTAMP
    """, nativeQuery = true)
    int completeIfLeaseValid(
            @Param("key") String key,
            @Param("token") UUID token,
            @Param("resultJson") String resultJson);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
    UPDATE idempotency_records
    SET status = 'FAILED',
        result_json = NULL,
        completed_at = NULL,
        lease_until = NULL
    WHERE idempotency_key = :key
      AND lease_token = :token
      AND status = 'IN_PROGRESS'
    """, nativeQuery = true)
    int releaseIfLeaseValid(
            @Param("key") String key,
            @Param("token") UUID token);
}