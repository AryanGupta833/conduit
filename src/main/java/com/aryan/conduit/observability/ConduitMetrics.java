package com.aryan.conduit.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Centralized, best-effort application metrics. Instrumentation must never affect task execution. */
@Component
public class ConduitMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicLong activeWorkflows = new AtomicLong();
    private final AtomicLong queueStreamEntries = new AtomicLong();
    private final AtomicLong queuePending = new AtomicLong();

    public ConduitMetrics(MeterRegistry registry) {
        this.registry = registry;
        safe(() -> Gauge.builder("conduit.worker.active.tasks", activeWorkers, AtomicInteger::get)
                .description("Tasks currently being processed by this application instance")
                .register(registry));
        safe(() -> Gauge.builder("conduit.workflow.active.executions", activeWorkflows, AtomicLong::get).register(registry));
        safe(() -> Gauge.builder("conduit.task.queue.stream.entries", queueStreamEntries, AtomicLong::get).register(registry));
        safe(() -> Gauge.builder("conduit.task.queue.pending", queuePending, AtomicLong::get).register(registry));
    }

    public void workflowStarted() {
        safe(() -> {
            registry.counter("conduit.workflow.starts").increment();
            activeWorkflows.incrementAndGet();
        });
    }

    public void workflowFinished(String status, long durationNanos) {
        safe(() -> {
            registry.counter("conduit.workflow.executions", "status", normalize(status)).increment();
            Timer.builder("conduit.workflow.execution.duration")
                    .tag("status", normalize(status)).publishPercentileHistogram()
                    .register(registry).record(Math.max(0, durationNanos), java.util.concurrent.TimeUnit.NANOSECONDS);
            activeWorkflows.updateAndGet(value -> Math.max(0, value - 1));
        });
    }

    public void taskFinished(String pluginType, String status, long durationNanos) {
        safe(() -> {
            registry.counter("conduit.task.executions", "plugin_type", normalizePluginType(pluginType), "status", normalizeStatus(status)).increment();
            Timer.builder("conduit.task.execution.duration")
                    .tag("plugin_type", normalizePluginType(pluginType)).tag("status", normalizeStatus(status)).publishPercentileHistogram()
                    .register(registry).record(Math.max(0, durationNanos), java.util.concurrent.TimeUnit.NANOSECONDS);
        });
    }

    public void taskRetry(String pluginType) {
        safe(() -> registry.counter("conduit.task.retries", "plugin_type", normalizePluginType(pluginType)).increment());
    }

    public void setActiveWorkflows(long value) { safe(() -> activeWorkflows.set(Math.max(0, value))); }
    public void setQueueSnapshot(long entries, long pending) {
        safe(() -> { queueStreamEntries.set(Math.max(0, entries)); queuePending.set(Math.max(0, pending)); });
    }

    public void workerStarted() { safe(activeWorkers::incrementAndGet); }
    public void workerFinished(String status) {
        safe(() -> {
            activeWorkers.updateAndGet(value -> Math.max(0, value - 1));
            registry.counter("conduit.worker.tasks.processed", "status", normalize(status)).increment();
        });
    }

    public void backendFinished(String backend, String status, long durationNanos) {
        safe(() -> {
            String normalizedBackend = normalize(backend);
            String normalizedStatus = normalize(status);
            registry.counter("conduit.backend.executions", "backend", normalizedBackend, "status", normalizedStatus).increment();
            Timer.builder("conduit.backend.execution.duration")
                    .tag("backend", normalizedBackend).tag("status", normalizedStatus)
                    .publishPercentileHistogram().register(registry)
                    .record(Math.max(0, durationNanos), java.util.concurrent.TimeUnit.NANOSECONDS);
        });
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizePluginType(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String normalizeStatus(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private void safe(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) { /* metrics are best effort */ }
    }
}
