package com.aryan.conduit.execution;

public record TaskExecutionResult(
        String executionId,
        String stdout,
        String stderr,
        int exitCode,
        boolean timedOut
) {
    public boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }
}