package com.aryan.conduit.execution.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryPolicyTest {
    @Test
    void exponentialBackoffSaturatesAtMaximumWithoutOverflow() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxRetries(Integer.MAX_VALUE)
                .backoffStrategy(RetryBackoffStrategy.EXPONENTIAL)
                .initialDelayMs(1000)
                .maxDelayMs(10_000)
                .jitter(false)
                .build();

        assertEquals(10_000L, policy.calculateDelay(64));
        assertEquals(10_000L, policy.calculateDelay(Integer.MAX_VALUE));
    }
}
