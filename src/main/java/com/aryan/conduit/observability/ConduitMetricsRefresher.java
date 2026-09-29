package com.aryan.conduit.observability;

import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConduitMetricsRefresher {
    private final WorkflowExecutionRepository workflows;
    private final TaskQueueService queue;
    private final ConduitMetrics metrics;

    public ConduitMetricsRefresher(WorkflowExecutionRepository workflows, TaskQueueService queue,
                                   ConduitMetrics metrics) {
        this.workflows = workflows;
        this.queue = queue;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 1000)
    public void refresh() {
        try {
            metrics.setActiveWorkflows(workflows.countByStatus(WorkflowExecutionStatus.RUNNING));
        } catch (RuntimeException ignored) { }
        try {
            metrics.setQueueSnapshot(queue.streamEntryCount(), queue.pendingCount());
        } catch (RuntimeException ignored) { }
    }
}
