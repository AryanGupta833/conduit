package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.IdempotencyRecord;
import com.aryan.conduit.execution.entity.IdempotencyStatus;
import com.aryan.conduit.execution.repository.IdempotencyRecordRepository;
import com.aryan.conduit.plugin.PluginResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;


    @Transactional
    public boolean tryStart(String idempotencyKey) {

        Optional<IdempotencyRecord> existing =
                repository.findByIdempotencyKey(idempotencyKey);

        if (existing.isPresent()) {

            IdempotencyRecord record = existing.get();

            /*
             * Completed operations must never execute again.
             */
            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                return false;
            }

            /*
             * Existing execution.
             */
            if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {

                /*
                 * Valid lease → another worker owns it.
                 */
                if (record.getLeaseUntil() != null
                        && record.getLeaseUntil()
                        .isAfter(LocalDateTime.now())) {

                    return false;
                }

                /*
                 * Expired lease → attempt atomic reclamation.
                 */
                return repository.reclaimExpiredLease(
                        idempotencyKey
                ) == 1;
            }

            /*
             * Previous execution failed.
             * Atomically reacquire with a fresh lease.
             */
            if (record.getStatus() == IdempotencyStatus.FAILED) {

                return repository.reacquireFailed(
                        idempotencyKey
                ) == 1;
            }
        }

        /*
         * No record exists.
         * Atomically create one with a 60-second lease.
         */
        return repository.insertIfAbsent(idempotencyKey) == 1;
    }

    @Transactional(readOnly = true)
    public Optional<PluginResult> getCompletedResult(
            String idempotencyKey) {

        return repository.findByIdempotencyKey(idempotencyKey)
                .filter(record ->
                        record.getStatus()
                                == IdempotencyStatus.COMPLETED)
                .map(this::deserializeResult);
    }

    @Transactional
    public void complete(
            String idempotencyKey,
            PluginResult result) {

        IdempotencyRecord record =
                repository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Idempotency record not found: "
                                                + idempotencyKey));

        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setResultJson(serializeResult(result));
        record.setCompletedAt(LocalDateTime.now());

        repository.save(record);
    }

    @Transactional
    public void release(String idempotencyKey) {

        IdempotencyRecord record =
                repository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Idempotency record not found: "
                                                + idempotencyKey));

        /*
         * Only IN_PROGRESS executions can be released.
         *
         * If the operation is already COMPLETED, we must
         * never turn it back into FAILED.
         */
        if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {

            record.setStatus(IdempotencyStatus.FAILED);
            record.setResultJson(null);
            record.setCompletedAt(null);

            repository.save(record);
        }
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