package com.aryan.conduit.execution.queue;

import org.springframework.data.redis.connection.stream.RecordId;

public record StreamMessage(
        RecordId recordId,
        TaskMessage taskMessage
) {
}