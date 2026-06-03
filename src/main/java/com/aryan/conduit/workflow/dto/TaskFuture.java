package com.aryan.conduit.workflow.dto;

import java.util.concurrent.Future;

public record TaskFuture(
        Future<?> future,
        Long taskExecutionId,
        Integer timeoutSeconds
) {
}
