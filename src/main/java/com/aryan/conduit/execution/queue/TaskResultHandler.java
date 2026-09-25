package com.aryan.conduit.execution.queue;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.queue.TaskResult;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.service.DistributedWorkflowCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskResultHandler {

    private final TaskExecutionRepository taskExecutionRepository;
    private final DistributedWorkflowCoordinator workflowCoordinator;

    @Transactional
    public void handle(TaskResult result) {

        TaskExecution taskExecution =
                taskExecutionRepository
                        .findById(result.taskExecutionId())
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Task execution not found: "
                                                + result.taskExecutionId()
                                )
                        );

        if (result.timeout()) {

            taskExecution.setStatus(
                    TaskExecutionStatus.TIMEOUT
            );

        } else if (result.success()) {

            taskExecution.setStatus(
                    TaskExecutionStatus.SUCCESS
            );

        } else {

            taskExecution.setStatus(
                    TaskExecutionStatus.FAILED
            );
        }

        taskExecutionRepository.save(taskExecution);

        workflowCoordinator.handleTaskCompletion(
                result.workflowExecutionId(),
                result.taskNodeId()
        );
    }
}