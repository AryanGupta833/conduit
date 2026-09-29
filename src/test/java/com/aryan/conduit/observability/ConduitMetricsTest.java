package com.aryan.conduit.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConduitMetricsTest {
    @Test
    void recordsWorkflowTaskRetryBackendAndWorkerMeasurements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConduitMetrics metrics = new ConduitMetrics(registry);

        metrics.workflowStarted();
        metrics.workflowFinished("SUCCESS", 1_000_000_000L);
        metrics.taskFinished("http", "FAILED", 500_000_000L);
        metrics.taskRetry("http");
        metrics.backendFinished("docker", "success", 250_000_000L);
        metrics.workerStarted();
        metrics.workerFinished("success");

        assertEquals(1.0, registry.get("conduit.workflow.starts").counter().count());
        assertEquals(1.0, registry.get("conduit.workflow.executions").tag("status", "success").counter().count());
        assertEquals(1.0, registry.get("conduit.task.executions").tag("plugin_type", "http").tag("status", "failed").counter().count());
        assertEquals(1.0, registry.get("conduit.task.retries").tag("plugin_type", "http").counter().count());
        assertEquals(1.0, registry.get("conduit.backend.executions").tag("backend", "docker").tag("status", "success").counter().count());
        assertEquals(0.0, registry.get("conduit.worker.active.tasks").gauge().value());
        assertEquals(1.0, registry.get("conduit.worker.tasks.processed").tag("status", "success").counter().count());
    }
}
