package com.aryan.conduit.execution.retry;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RetryPolicy {

    private final int maxRetries;

    private final RetryBackoffStrategy backoffStrategy;

    private final long initialDelayMs;

    private final long maxDelayMs;

    private final boolean jitter;

    public long calculateDelay(int retryNumber) {

        long cap = Math.max(0L, maxDelayMs);
        long delay = Math.min(Math.max(0L, initialDelayMs), cap);
        if (backoffStrategy == RetryBackoffStrategy.EXPONENTIAL && delay > 0) {
            for (int i = 1; i < retryNumber && delay < cap; i++) {
                delay = delay > cap / 2 ? cap : delay * 2;
            }
        }

        if (jitter) {
            double multiplier = 0.5 + Math.random();
            delay = (long) (delay * multiplier);
        }

        return Math.max(0L, Math.min(delay, cap));
    }

    public boolean shouldRetry(int retryNumber) {
        return retryNumber <= maxRetries;
    }
}
