package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.IdempotencyRecord;
import com.aryan.conduit.execution.entity.IdempotencyStatus;
import com.aryan.conduit.execution.repository.IdempotencyRecordRepository;
import com.aryan.conduit.plugin.PluginResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;


    @Transactional
    public Optional<UUID> tryStart(String idempotencyKey) {

        Optional<IdempotencyRecord> existing =
                repository.findByIdempotencyKey(idempotencyKey);

        if (existing.isPresent()) {

            IdempotencyRecord record = existing.get();

            /*
             * Completed work must never execute again.
             */
            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                return Optional.empty();
            }

            /*
             * Existing lease:
             *
             * Let the database decide whether the lease
             * is expired. If expired, atomically reclaim it
             * and generate a new fencing token.
             */
            if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {

                int reclaimed =
                        repository.reclaimExpiredLease(idempotencyKey);

                if (reclaimed != 1) {
                    return Optional.empty();
                }

                return repository
                        .findByIdempotencyKey(idempotencyKey)
                        .map(IdempotencyRecord::getLeaseToken);
            }

            /*
             * Failed execution can be reacquired.
             * A fresh fencing token is generated.
             */
            if (record.getStatus() == IdempotencyStatus.FAILED) {

                int reacquired =
                        repository.reacquireFailed(idempotencyKey);

                if (reacquired != 1) {
                    return Optional.empty();
                }

                return repository
                        .findByIdempotencyKey(idempotencyKey)
                        .map(IdempotencyRecord::getLeaseToken);
            }
        }

        /*
         * No existing record.
         *
         * INSERT ... ON CONFLICT DO NOTHING makes
         * this safe under concurrent requests.
         */
        int inserted =
                repository.insertIfAbsent(idempotencyKey);

        if (inserted != 1) {
            return Optional.empty();
        }

        return repository
                .findByIdempotencyKey(idempotencyKey)
                .map(IdempotencyRecord::getLeaseToken);
    }


    @Transactional(readOnly = true)
    public Optional<PluginResult> getCompletedResult(
            String idempotencyKey) {

        return repository.findByIdempotencyKey(idempotencyKey)
                .filter(record ->
                        record.getStatus() == IdempotencyStatus.COMPLETED)
                .map(this::deserializeResult);
    }


    @Transactional
    public boolean complete(
            String idempotencyKey,
            UUID leaseToken,
            String resultJson) {

        /*
         * Completion is fenced by the lease token.
         *
         * A stale worker cannot complete work after
         * another worker has reclaimed the lease.
         */
        return repository.completeIfLeaseValid(
                idempotencyKey,
                leaseToken,
                resultJson
        ) == 1;
    }


    @Transactional
    public void release(
            String idempotencyKey,
            UUID leaseToken) {

        /*
         * Release must also be fenced.
         *
         * A stale worker must not be able to mark
         * another worker's active lease as FAILED.
         */
        repository.releaseIfLeaseValid(
                idempotencyKey,
                leaseToken
        );
    }


    @Transactional
    public boolean renewLease(
            String idempotencyKey,
            UUID leaseToken) {

        /*
         * Only the worker holding the current token
         * can renew the lease.
         */
        return repository.renewLease(
                idempotencyKey,
                leaseToken
        ) == 1;
    }


    private String serializeResult(PluginResult result) {

        try {

            return objectMapper.writeValueAsString(result);

        } catch (JsonProcessingException e) {

            throw new IllegalStateException(
                    "Failed to serialize plugin result",
                    e
            );
        }
    }


    private PluginResult deserializeResult(
            IdempotencyRecord record) {

        try {

            return objectMapper.readValue(
                    record.getResultJson(),
                    PluginResult.class
            );

        } catch (JsonProcessingException e) {

            throw new IllegalStateException(
                    "Failed to deserialize plugin result",
                    e
            );
        }
    }
}