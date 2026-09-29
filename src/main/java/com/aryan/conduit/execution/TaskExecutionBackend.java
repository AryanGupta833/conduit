package com.aryan.conduit.execution;

import java.time.Duration;
import java.util.List;

public interface TaskExecutionBackend {

    TaskExecutionResult execute(
            String image,
            List<String> command,
            Duration timeout
    );
}