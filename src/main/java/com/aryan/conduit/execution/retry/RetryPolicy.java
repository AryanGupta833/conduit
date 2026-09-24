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

        long delay;

        if (backoffStrategy == RetryBackoffStrategy.EXPONENTIAL) {
            delay = initialDelayMs * (1L << Math.max(0, retryNumber - 1));
        } else {
            delay = initialDelayMs;
        }

        if (jitter) {
            double multiplier = 0.5 + Math.random();
            delay = (long) (delay * multiplier);
        }

        return Math.min(delay, maxDelayMs);
    }

    public boolean shouldRetry(int retryNumber) {
        return retryNumber <= maxRetries;
    }
}