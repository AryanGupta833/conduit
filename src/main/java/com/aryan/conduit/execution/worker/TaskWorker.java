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
import com.aryan.conduit.execution.service.TaskRunnerService;
import com.aryan.conduit.observability.ConduitMetrics;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);

    private final TaskQueueService taskQueueService;
    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskRunnerService taskRunnerService;
    private final TaskResultHandler taskResultHandler;
    private final ConduitMetrics metrics;

    private final String consumerName =
            "worker-" + UUID.randomUUID();

    @Scheduled(fixedDelay = 1000)
    public void poll() {

        List<StreamMessage> messages =
                taskQueueService.read(
                        consumerName
                );

        for (StreamMessage message : messages) {

            process(
                    message
            );
        }
    }

    public void process(
            StreamMessage streamMessage
    ) {

        metrics.workerStarted();
        String outcome = "failed";
        boolean acknowledge = false;

        TaskMessage message =
                streamMessage.taskMessage();

        try {

            TaskExecution taskExecution =
                    taskExecutionRepository
                            .findById(
                                    message.taskExecutionId()
                            )
                            .orElseThrow();

            if (isTerminal(taskExecution.getStatus())) {
                outcome = "duplicate";
                acknowledge = true;
            } else {
                taskExecution.setStatus(TaskExecutionStatus.RUNNING);
                taskExecutionRepository.save(taskExecution);

                taskRunnerService.executeWithRetry(
                        taskExecution.getId(),
                        taskExecution.getTaskNode().getMaxRetries(),
                        taskExecution.getTaskNode().getTimeoutSeconds()
                );

                taskResultHandler.handle(new TaskResult(
                        message.workflowExecutionId(),
                        message.taskExecutionId(),
                        message.taskNodeId(),
                        true,
                        false,
                        null
                ));
                outcome = "success";
                acknowledge = true;
            }

        } catch (TaskAlreadyInProgressException e) {
            log.debug("Deferring duplicate task delivery while its lease is active: {}", message.taskExecutionId());
            outcome = "deferred";
        } catch (Exception e) {
            log.error("Task processing failed for task execution {}", message.taskExecutionId(), e);
            taskResultHandler.handle(new TaskResult(
                    message.workflowExecutionId(),
                    message.taskExecutionId(),
                    message.taskNodeId(),
                    false,
                    false,
                    e.getMessage()
            ));
            acknowledge = true;
        } finally {
            try {
                if (acknowledge) taskQueueService.acknowledge(streamMessage.recordId());
            } finally {
                metrics.workerFinished(outcome);
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
