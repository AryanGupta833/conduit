package com.aryan.conduit.execution.queue;

import java.util.List;

public record QueueOperationsResponse(String stream, String consumerGroup, long retainedEntries,
                                      long pendingDeliveries, String lastDeliveredId,
                                      List<ConsumerState> consumers) {
    public record ConsumerState(String name, long pendingDeliveries, long idleMillis) { }
}
