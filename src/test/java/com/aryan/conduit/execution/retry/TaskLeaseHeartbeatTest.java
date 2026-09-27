package com.aryan.conduit.execution.retry;

import com.aryan.conduit.execution.service.IdempotencyService;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class TaskLeaseHeartbeatTest {

    @Test
    void shouldRenewLeaseSuccessfully() throws Exception {

        IdempotencyService idempotencyService =
                mock(IdempotencyService.class);

        String key = "heartbeat-success";
        UUID token = UUID.randomUUID();

        when(idempotencyService.renewLease(key, token))
                .thenReturn(true);

        TaskLeaseHeartbeat heartbeat =
                new TaskLeaseHeartbeat(
                        idempotencyService,
                        key,
                        token
                );

        heartbeat.start(
                0,
                50,
                TimeUnit.MILLISECONDS
        );

        Thread.sleep(150);

        assertFalse(heartbeat.isLeaseLost());

        verify(
                idempotencyService,
                atLeastOnce()
        ).renewLease(key, token);

        heartbeat.stop();
    }

    @Test
    void shouldMarkLeaseLostWhenRenewalFails() throws Exception {

        IdempotencyService idempotencyService =
                mock(IdempotencyService.class);

        String key = "heartbeat-loss";
        UUID token = UUID.randomUUID();

        when(idempotencyService.renewLease(key, token))
                .thenReturn(false);

        TaskLeaseHeartbeat heartbeat =
                new TaskLeaseHeartbeat(
                        idempotencyService,
                        key,
                        token
                );

        heartbeat.start(
                0,
                50,
                TimeUnit.MILLISECONDS
        );

        Thread.sleep(150);

        assertTrue(heartbeat.isLeaseLost());

        verify(
                idempotencyService,
                atLeastOnce()
        ).renewLease(key, token);

        heartbeat.stop();
    }

    @Test
    void shouldMarkLeaseLostWhenRenewalThrowsException()
            throws Exception {

        IdempotencyService idempotencyService =
                mock(IdempotencyService.class);

        String key = "heartbeat-exception";
        UUID token = UUID.randomUUID();

        when(idempotencyService.renewLease(key, token))
                .thenThrow(
                        new RuntimeException(
                                "Database unavailable"
                        )
                );

        TaskLeaseHeartbeat heartbeat =
                new TaskLeaseHeartbeat(
                        idempotencyService,
                        key,
                        token
                );

        heartbeat.start(
                0,
                50,
                TimeUnit.MILLISECONDS
        );

        Thread.sleep(150);

        assertTrue(heartbeat.isLeaseLost());

        verify(
                idempotencyService,
                atLeastOnce()
        ).renewLease(key, token);

        heartbeat.stop();
    }

    @Test
    void shouldStopRenewingAfterLeaseIsLost()
            throws Exception {

        IdempotencyService idempotencyService =
                mock(IdempotencyService.class);

        String key = "heartbeat-stop-after-loss";
        UUID token = UUID.randomUUID();

        when(idempotencyService.renewLease(key, token))
                .thenReturn(false);

        TaskLeaseHeartbeat heartbeat =
                new TaskLeaseHeartbeat(
                        idempotencyService,
                        key,
                        token
                );

        heartbeat.start(
                0,
                50,
                TimeUnit.MILLISECONDS
        );

        Thread.sleep(150);

        assertTrue(heartbeat.isLeaseLost());

        int callsAfterFailure =
                mockingDetails(idempotencyService)
                        .getInvocations()
                        .size();

        Thread.sleep(150);

        int callsAfterWait =
                mockingDetails(idempotencyService)
                        .getInvocations()
                        .size();

        assertTrue(
                callsAfterWait <= callsAfterFailure
        );

        heartbeat.stop();
    }

    @Test
    void shouldStopHeartbeatExplicitly() throws Exception {

        IdempotencyService idempotencyService =
                mock(IdempotencyService.class);

        String key = "heartbeat-explicit-stop";
        UUID token = UUID.randomUUID();

        when(idempotencyService.renewLease(key, token))
                .thenReturn(true);

        TaskLeaseHeartbeat heartbeat =
                new TaskLeaseHeartbeat(
                        idempotencyService,
                        key,
                        token
                );

        heartbeat.start(
                0,
                50,
                TimeUnit.MILLISECONDS
        );

        Thread.sleep(100);

        heartbeat.stop();

        int callsAtStop =
                mockingDetails(idempotencyService)
                        .getInvocations()
                        .size();

        Thread.sleep(150);

        int callsAfterStop =
                mockingDetails(idempotencyService)
                        .getInvocations()
                        .size();

        assertFalse(heartbeat.isLeaseLost());

        assertTrue(
                callsAfterStop <= callsAtStop
        );
    }
}