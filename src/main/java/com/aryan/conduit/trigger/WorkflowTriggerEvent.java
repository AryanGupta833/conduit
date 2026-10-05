package com.aryan.conduit.trigger;

import java.util.UUID;

public record WorkflowTriggerEvent(UUID eventId, Long workflowId) {
    /** Compatibility constructor for internal callers and old payload formats. */
    public WorkflowTriggerEvent(Long workflowId) {
        this(UUID.randomUUID(), workflowId);
    }
}
