package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.IdempotencyRecord;
import com.aryan.conduit.execution.entity.IdempotencyStatus;
import com.aryan.conduit.execution.repository.IdempotencyRecordRepository;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository repository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private IdempotencyService idempotencyService;

    @Test
    void shouldAcquireLeaseForNewIdempotencyKey() {

        String key = "workflow-1:task-1";
        UUID leaseToken = UUID.randomUUID();

        IdempotencyRecord record =
                IdempotencyRecord.builder()
                        .idempotencyKey(key)
                        .status(IdempotencyStatus.IN_PROGRESS)
                        .leaseToken(leaseToken)
                        .build();

        when(repository.findByIdempotencyKey(key))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(record)
                );

        when(repository.insertIfAbsent(key))
                .thenReturn(1);

        Optional<UUID> result =
                idempotencyService.tryStart(key);

        assertTrue(result.isPresent());
        assertEquals(
                leaseToken,
                result.get()
        );

        verify(repository)
                .insertIfAbsent(key);
    }

    @Test
    void shouldNotAcquireLeaseWhenKeyIsAlreadyCompleted() {

        String key = "workflow-1:task-1";

        IdempotencyRecord record =
                IdempotencyRecord.builder()
                        .idempotencyKey(key)
                        .status(IdempotencyStatus.COMPLETED)
                        .build();

        when(repository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(record));

        Optional<UUID> result =
                idempotencyService.tryStart(key);

        assertTrue(result.isEmpty());

        verify(repository, never())
                .insertIfAbsent(anyString());

        verify(repository, never())
                .reclaimExpiredLease(anyString());
    }

    @Test
    void shouldNotAcquireLeaseWhenActiveLeaseCannotBeReclaimed() {

        String key = "workflow-1:task-1";

        IdempotencyRecord record =
                IdempotencyRecord.builder()
                        .idempotencyKey(key)
                        .status(IdempotencyStatus.IN_PROGRESS)
                        .leaseToken(UUID.randomUUID())
                        .build();

        when(repository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(record));

        when(repository.reclaimExpiredLease(key))
                .thenReturn(0);

        Optional<UUID> result =
                idempotencyService.tryStart(key);

        assertTrue(result.isEmpty());

        verify(repository)
                .reclaimExpiredLease(key);
    }

    @Test
    void shouldAcquireNewFencingTokenWhenExpiredLeaseIsReclaimed() {

        String key = "workflow-1:task-1";

        UUID oldToken = UUID.randomUUID();
        UUID newToken = UUID.randomUUID();

        IdempotencyRecord existingRecord =
                IdempotencyRecord.builder()
                        .idempotencyKey(key)
                        .status(IdempotencyStatus.IN_PROGRESS)
                        .leaseToken(oldToken)
                        .build();

        IdempotencyRecord reclaimedRecord =
                IdempotencyRecord.builder()
                        .idempotencyKey(key)
                        .status(IdempotencyStatus.IN_PROGRESS)
                        .leaseToken(newToken)
                        .build();

        when(repository.findByIdempotencyKey(key))
                .thenReturn(
                        Optional.of(existingRecord),
                        Optional.of(reclaimedRecord)
                );

        when(repository.reclaimExpiredLease(key))
                .thenReturn(1);

        Optional<UUID> result =
                idempotencyService.tryStart(key);

        assertTrue(result.isPresent());

        assertEquals(
                newToken,
                result.get()
        );

        assertNotEquals(
                oldToken,
                result.get()
        );

        verify(repository)
                .reclaimExpiredLease(key);
    }

    @Test
    void staleWorkerMustNotCompleteAfterLeaseWasReclaimed() {

        String key = "workflow-1:task-1";

        UUID staleToken = UUID.randomUUID();

        when(repository.completeIfLeaseValid(
                key,
                staleToken,
                "{\"success\":true}"
        )).thenReturn(0);

        boolean completed =
                idempotencyService.complete(
                        key,
                        staleToken,
                        "{\"success\":true}"
                );

        assertFalse(completed);

        verify(repository)
                .completeIfLeaseValid(
                        key,
                        staleToken,
                        "{\"success\":true}"
                );
    }

    @Test
    void currentWorkerMustBeAbleToCompleteWithValidLease() {

        String key = "workflow-1:task-1";

        UUID currentToken = UUID.randomUUID();

        when(repository.completeIfLeaseValid(
                key,
                currentToken,
                "{\"success\":true}"
        )).thenReturn(1);

        boolean completed =
                idempotencyService.complete(
                        key,
                        currentToken,
                        "{\"success\":true}"
                );

        assertTrue(completed);

        verify(repository)
                .completeIfLeaseValid(
                        key,
                        currentToken,
                        "{\"success\":true}"
                );
    }

    @Test
    void staleWorkerMustNotRenewNewWorkersLease() {

        String key = "workflow-1:task-1";

        UUID staleToken = UUID.randomUUID();

        when(repository.renewLease(
                key,
                staleToken
        )).thenReturn(0);

        boolean renewed =
                idempotencyService.renewLease(
                        key,
                        staleToken
                );

        assertFalse(renewed);

        verify(repository)
                .renewLease(
                        key,
                        staleToken
                );
    }

    @Test
    void currentWorkerMustBeAbleToRenewLease() {

        String key = "workflow-1:task-1";

        UUID currentToken = UUID.randomUUID();

        when(repository.renewLease(
                key,
                currentToken
        )).thenReturn(1);

        boolean renewed =
                idempotencyService.renewLease(
                        key,
                        currentToken
                );

        assertTrue(renewed);

        verify(repository)
                .renewLease(
                        key,
                        currentToken
                );
    }

    @Test
    void staleWorkerMustNotReleaseNewWorkersLease() {

        String key = "workflow-1:task-1";

        UUID staleToken = UUID.randomUUID();

        idempotencyService.release(
                key,
                staleToken
        );

        verify(repository)
                .releaseIfLeaseValid(
                        key,
                        staleToken
                );
    }

    @Test
    void shouldReturnCompletedPluginResult() throws Exception {

        String key = "workflow-1:task-1";

        PluginResult pluginResult =
                PluginResult.builder()
                        .success(true)
                        .output("task completed")
                        .variables(
                                new HashMap<>(
                                        Map.of(
                                                "value",
                                                42
                                        )
                                )
                        )
                        .metadata(
                                new HashMap<>(
                                        Map.of(
                                                "worker",
                                                "worker-1"
                                        )
                                )
                        )
                        .build();

        String resultJson =
                "{\"success\":true,\"output\":\"task completed\"}";

        IdempotencyRecord record =
                IdempotencyRecord.builder()
                        .idempotencyKey(key)
                        .status(IdempotencyStatus.COMPLETED)
                        .resultJson(resultJson)
                        .build();

        when(repository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(record));

        when(objectMapper.readValue(
                resultJson,
                PluginResult.class
        )).thenReturn(pluginResult);

        Optional<PluginResult> result =
                idempotencyService.getCompletedResult(key);

        assertTrue(result.isPresent());

        assertEquals(
                pluginResult,
                result.get()
        );

        verify(objectMapper)
                .readValue(
                        resultJson,
                        PluginResult.class
                );
    }

    @Test
    void shouldReturnEmptyWhenResultIsNotCompleted() throws JsonProcessingException {

        String key = "workflow-1:task-1";

        IdempotencyRecord record =
                IdempotencyRecord.builder()
                        .idempotencyKey(key)
                        .status(IdempotencyStatus.IN_PROGRESS)
                        .build();

        when(repository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(record));

        Optional<PluginResult> result =
                idempotencyService.getCompletedResult(key);

        assertTrue(result.isEmpty());

        verify(objectMapper, never())
                .readValue(
                        anyString(),
                        eq(PluginResult.class)
                );
    }

    @Test
    void shouldReportExpiredLease() {

        String key = "workflow-1:task-1";

        when(repository.isLeaseExpired(key))
                .thenReturn(true);

        boolean expired =
                idempotencyService.isLeaseExpired(key);

        assertTrue(expired);

        verify(repository)
                .isLeaseExpired(key);
    }

    @Test
    void shouldReportActiveLease() {

        String key = "workflow-1:task-1";

        when(repository.isLeaseExpired(key))
                .thenReturn(false);

        boolean expired =
                idempotencyService.isLeaseExpired(key);

        assertFalse(expired);

        verify(repository)
                .isLeaseExpired(key);
    }
}
