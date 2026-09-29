package com.aryan.conduit.execution.retry;

import com.aryan.conduit.execution.service.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TaskLeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(TaskLeaseHeartbeat.class);

    private final IdempotencyService idempotencyService;
    private final String idempotencyKey;
    private final UUID leaseToken;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private final AtomicBoolean leaseLost =
            new AtomicBoolean(false);

    private final AtomicBoolean stopped =
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

    public void start(
            long initialDelay,
            long period,
            TimeUnit unit) {

        heartbeatTask =
                scheduler.scheduleAtFixedRate(
                        this::renew,
                        initialDelay,
                        period,
                        unit
                );
    }

    private void renew() {

        if (leaseLost.get() || stopped.get()) {
            return;
        }

        try {

            boolean renewed =
                    idempotencyService.renewLease(
                            idempotencyKey,
                            leaseToken
                    );

            if (!renewed) {

                leaseLost.set(true);

                log.warn("Lease lost for task key {}", idempotencyKey);

                stopHeartbeat();
            }

        } catch (Exception e) {

            leaseLost.set(true);

            log.warn("Lease heartbeat failed for task key {}", idempotencyKey, e);

            stopHeartbeat();
        }
    }

    public boolean isLeaseLost() {
        return leaseLost.get();
    }

    public void stop() {

        stopped.set(true);

        stopHeartbeat();

        scheduler.shutdownNow();
    }

    private void stopHeartbeat() {

        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
    }
}
