package com.aryan.conduit.execution.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TaskLeaseHeartbeatService {

    private final IdempotencyService idempotencyService;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4);

    private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeats =
            new ConcurrentHashMap<>();

    public void start(
            String idempotencyKey,
            UUID leaseToken
    ) {

        ScheduledFuture<?> future =
                scheduler.scheduleAtFixedRate(
                        () -> renew(
                                idempotencyKey,
                                leaseToken
                        ),
                        20,
                        20,
                        TimeUnit.SECONDS
                );

        ScheduledFuture<?> previous =
                heartbeats.putIfAbsent(
                        idempotencyKey,
                        future
                );

        if (previous != null) {
            future.cancel(false);
        }
    }

    public void stop(String idempotencyKey) {

        ScheduledFuture<?> future =
                heartbeats.remove(idempotencyKey);

        if (future != null) {
            future.cancel(false);
        }
    }

    private void renew(
            String idempotencyKey,
            UUID leaseToken
    ) {

        boolean renewed =
                idempotencyService.renewLease(
                        idempotencyKey,
                        leaseToken
                );

        if (!renewed) {
            stop(idempotencyKey);
        }
    }
}