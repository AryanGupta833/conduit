package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskDispatchService {

    private final TaskExecutionRepository taskExecutionRepository;
    private final OutboxService outboxService;

    @Transactional
    public boolean dispatch(
            TaskExecution taskExecution
    ) {

        int claimed =
                taskExecutionRepository.claimForQueue(
                        taskExecution.getId(),
                        TaskExecutionStatus.PENDING,
                        TaskExecutionStatus.QUEUED
                );

        if (claimed == 0) {
            return false;
        }

        TaskMessage message =
                new TaskMessage(
                        taskExecution
                                .getWorkflowExecution()
                                .getId(),

                        taskExecution.getId(),

                        taskExecution
                                .getTaskNode()
                                .getId()
                );

        outboxService.createTaskQueueEvent(
                message
        );

        return true;
    }
}