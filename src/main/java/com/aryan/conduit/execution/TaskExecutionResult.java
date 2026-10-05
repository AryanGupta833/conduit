package com.aryan.conduit.execution;

public record TaskExecutionResult(
        String executionId,
        String stdout,
        String stderr,
        int exitCode,
        boolean timedOut,
        boolean outputTruncated
) {
    public TaskExecutionResult(String executionId, String stdout, String stderr, int exitCode, boolean timedOut) {
        this(executionId, stdout, stderr, exitCode, timedOut, false);
    }
    public boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }
}
