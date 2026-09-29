package com.aryan.conduit.docker;

public record DockerExecutionResult(
        String containerId,
        String stdout,
        String stderr,
        int exitCode,
        boolean timedOut
) {
    public boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }
}