package com.aryan.conduit.execution.retry;

import org.springframework.stereotype.Component;

@Component
public class RetryPolicyFactory {

    public RetryPolicy defaultPolicy(Integer maxRetries) {

        int retries =
                maxRetries == null
                        ? 0
                        : Math.max(maxRetries, 0);

        return RetryPolicy.builder()
                .maxRetries(retries)
                .backoffStrategy(RetryBackoffStrategy.EXPONENTIAL)
                .initialDelayMs(1000)
                .maxDelayMs(10000)
                .jitter(true)
                .build();
    }
}