package com.aryan.conduit.execution.retry;

import com.aryan.conduit.execution.service.IdempotencyService;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TaskLeaseHeartbeat {

    private final IdempotencyService idempotencyService;
    private final String idempotencyKey;
    private final UUID leaseToken;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private final AtomicBoolean leaseLost =
            new AtomicBoolean(false);

    private ScheduledFuture<?> heartbeatTask;

    public TaskLeaseHeartbeat(
            IdempotencyService idempotencyService,
            String idempotencyKey,
            UUID leaseToken) {

        this.idempotencyService = idempotencyService;
        this.idempotencyKey = idempotencyKey;
        this.leaseToken = leaseToken;
    }

    public void start() {

        heartbeatTask = scheduler.scheduleAtFixedRate(
                this::renew,
                20,
                20,
                TimeUnit.SECONDS
        );
    }

    private void renew() {

        try {

            boolean renewed =
                    idempotencyService.renewLease(
                            idempotencyKey,
                            leaseToken
                    );

            if (!renewed) {

                leaseLost.set(true);

                System.err.println(
                        "LEASE LOST: " + idempotencyKey
                );
            }

        } catch (Exception e) {

            leaseLost.set(true);

            System.err.println(
                    "Lease heartbeat failed: "
                            + idempotencyKey
            );

            e.printStackTrace();
        }
    }

    public boolean isLeaseLost() {
        return leaseLost.get();
    }

    public void stop() {

        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }

        scheduler.shutdownNow();
    }
}