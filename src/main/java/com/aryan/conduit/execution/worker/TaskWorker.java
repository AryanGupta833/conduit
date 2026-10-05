package com.aryan.conduit.execution.worker;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.queue.StreamMessage;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.queue.TaskResult;
import com.aryan.conduit.execution.queue.TaskResultHandler;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.retry.TaskAlreadyInProgressException;
import com.aryan.conduit.execution.retry.TaskLeaseLostException;
import com.aryan.conduit.execution.retry.TaskExecutionCancelledException;
import com.aryan.conduit.execution.service.TaskRunnerService;
import com.aryan.conduit.execution.service.HumanTaskService;
import com.aryan.conduit.observability.ConduitMetrics;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

@Service
@RequiredArgsConstructor
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);

    private final TaskQueueService taskQueueService;
    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskRunnerService taskRunnerService;
    private final TaskResultHandler taskResultHandler;
    private final ConduitMetrics metrics;
    private final HumanTaskService humanTaskService;
    private AsyncTaskExecutor taskWorkerExecutor;

    @Autowired(required = false)
    void setTaskWorkerExecutor(@org.springframework.beans.factory.annotation.Qualifier("taskWorkerExecutor") AsyncTaskExecutor executor) {
        this.taskWorkerExecutor = executor;
    }

    private final String consumerName =
            "worker-" + UUID.randomUUID();

    @Scheduled(fixedDelay = 1000)
    public void poll() {

        List<StreamMessage> messages =
                taskQueueService.read(
                        consumerName
                );

        for (StreamMessage message : messages) {
            if (!submit(message)) break;
        }
    }

    /** Submit both newly read and reclaimed deliveries to the same bounded pool. */
    public boolean submit(StreamMessage message) {
        try {
            if (taskWorkerExecutor == null) process(message);
            else taskWorkerExecutor.execute(() -> process(message));
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("Task worker is saturated; leaving Redis delivery pending for recovery: {}",
                    message.taskMessage().taskExecutionId());
            return false;
        }
    }

    public void process(
            StreamMessage streamMessage
    ) {

        TaskMessage message = streamMessage.taskMessage();
        if (message.correlationId() != null && !message.correlationId().isBlank())
            MDC.put("correlationId", message.correlationId());
        else
            MDC.remove("correlationId");

        metrics.workerStarted();
        String outcome = "failed";
        boolean acknowledge = false;

        try {

            TaskExecution taskExecution =
                    taskExecutionRepository
                            .findById(
                                    message.taskExecutionId()
                            )
                            .orElseThrow();

            if (isTerminal(taskExecution.getStatus())) {
                taskResultHandler.reconcile(taskExecution.getId());
                outcome = "duplicate";
                acknowledge = true;
            } else if (taskExecution.getStatus() == TaskExecutionStatus.WAITING_FOR_HUMAN) {
                outcome = "waiting_for_human";
                acknowledge = true;
            } else if ("HUMAN_TASK".equalsIgnoreCase(taskExecution.getTaskNode().getPluginType())) {
                humanTaskService.createForTask(taskExecution.getId());
                outcome = "waiting_for_human";
                acknowledge = true;
            } else {
                java.util.Map<String, Object> resultData;
                try {
                    resultData = taskRunnerService.executeWithRetry(
                            taskExecution.getId(),
                            taskExecution.getTaskNode().getMaxRetries(),
                            taskExecution.getTaskNode().getTimeoutSeconds()
                    );
                } catch (TaskAlreadyInProgressException | TaskLeaseLostException | TaskExecutionCancelledException e) {
                    throw e;
                } catch (Exception executionFailure) {
                    log.error("Task execution failed for task execution {}", message.taskExecutionId(), executionFailure);
                    boolean timeout = executionFailure instanceof com.aryan.conduit.execution.retry.TaskTimeoutException;
                    taskResultHandler.handle(new TaskResult(
                            message.workflowExecutionId(), message.taskExecutionId(), message.taskNodeId(),
                            false, timeout, executionFailure.getMessage()));
                    outcome = timeout ? "timeout" : "failed";
                    acknowledge = true;
                    return;
                }

                try {
                    taskResultHandler.handle(new TaskResult(
                            message.workflowExecutionId(), message.taskExecutionId(), message.taskNodeId(),
                            true, false, null, resultData));
                    outcome = "success";
                    acknowledge = true;
                } catch (RuntimeException completionFailure) {
                    if (completionFailure instanceof com.aryan.conduit.execution.service.ExecutionContextLimitException) {
                        taskResultHandler.handle(new TaskResult(
                                message.workflowExecutionId(), message.taskExecutionId(), message.taskNodeId(),
                                false, false, completionFailure.getMessage()));
                        outcome = "context_limit";
                        acknowledge = true;
                        return;
                    }
                    log.error("Task result was produced but context/coordinator completion could not be committed; leaving delivery pending: {}",
                            message.taskExecutionId(), completionFailure);
                    outcome = "completion_deferred";
                }
            }

        } catch (TaskAlreadyInProgressException e) {
            log.debug("Deferring duplicate task delivery while its lease is active: {}", message.taskExecutionId());
            outcome = "deferred";
        } catch (TaskLeaseLostException e) {
            // A newer worker may own the lease. Never publish a failure result
            // or ACK this delivery on behalf of the stale worker.
            log.debug("Deferring task delivery after lease loss: {}", message.taskExecutionId());
            outcome = "deferred";
        } catch (TaskExecutionCancelledException e) {
            log.debug("Acknowledging task cancelled before it started: {}", message.taskExecutionId());
            outcome = "cancelled";
            acknowledge = true;
        } catch (Exception e) {
            log.error("Task processing failed for task execution {}", message.taskExecutionId(), e);
            // Result persistence failures leave the delivery pending for retry.
        } finally {
            try {
                if (acknowledge) taskQueueService.acknowledge(streamMessage.recordId());
            } finally {
                metrics.workerFinished(outcome);
                MDC.remove("correlationId");
            }
        }
    }

    private boolean isTerminal(TaskExecutionStatus status) {
        return status == TaskExecutionStatus.SUCCESS
                || status == TaskExecutionStatus.FAILED
                || status == TaskExecutionStatus.TIMEOUT
                || status == TaskExecutionStatus.SKIPPED;
    }

}
