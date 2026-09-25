package com.aryan.conduit.execution.worker;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.queue.StreamMessage;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.queue.TaskResult;
import com.aryan.conduit.execution.queue.TaskResultHandler;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.service.TaskRunnerService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskWorker {

    private final TaskQueueService taskQueueService;
    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskRunnerService taskRunnerService;
    private final TaskResultHandler taskResultHandler;

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

        TaskMessage message =
                streamMessage.taskMessage();

        try {

            TaskExecution taskExecution =
                    taskExecutionRepository
                            .findById(
                                    message.taskExecutionId()
                            )
                            .orElseThrow();

            taskExecution.setStatus(
                    TaskExecutionStatus.RUNNING
            );

            taskExecutionRepository.save(
                    taskExecution
            );

            taskRunnerService.executeWithRetry(
                    taskExecution.getId(),
                    taskExecution
                            .getTaskNode()
                            .getMaxRetries(),
                    taskExecution
                            .getTaskNode()
                            .getTimeoutSeconds()
            );

            taskResultHandler.handle(
                    new TaskResult(
                            message.workflowExecutionId(),
                            message.taskExecutionId(),
                            message.taskNodeId(),
                            true,
                            false,
                            null
                    )
            );

            taskQueueService.acknowledge(
                    streamMessage.recordId()
            );

        } catch (Exception e) {

            taskResultHandler.handle(
                    new TaskResult(
                            message.workflowExecutionId(),
                            message.taskExecutionId(),
                            message.taskNodeId(),
                            false,
                            false,
                            e.getMessage()
                    )
            );

            /*
             * We ACK here because TaskResultHandler has
             * transitioned the task to FAILED.
             *
             * Actual retry/recovery of abandoned messages
             * will be implemented separately.
             */
            taskQueueService.acknowledge(
                    streamMessage.recordId()
            );
        }
    }
}