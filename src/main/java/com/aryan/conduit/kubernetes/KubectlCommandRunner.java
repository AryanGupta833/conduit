package com.aryan.conduit.kubernetes;

import java.time.Duration;
import java.util.List;

interface KubectlCommandRunner {
    CommandResult run(List<String> command, String stdin, Duration timeout);

    record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean outputTruncated) {
        CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) { this(exitCode, stdout, stderr, timedOut, false); }
    }
}
