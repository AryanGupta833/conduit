package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DistributedWorkflowCoordinator {

    private final TaskExecutionRepository taskExecutionRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final DependencyRepository dependencyRepository;
    private final ExecutionRuntimeService executionRuntimeService;
    private final TaskQueueService taskQueueService;

    @Transactional
    public void handleTaskCompletion(
            Long workflowExecutionId,
            Long completedTaskId
    ) {

        WorkflowExecution workflowExecution =
                workflowExecutionRepository
                        .findById(workflowExecutionId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Workflow execution not found: "
                                                + workflowExecutionId
                                )
                        );

        if (workflowExecution.getStatus()
                == WorkflowExecutionStatus.CANCELLED) {
            return;
        }

        List<TaskExecution> taskExecutions =
                taskExecutionRepository
                        .findByWorkflowExecution_Id(
                                workflowExecutionId
                        );

        Map<Long, TaskExecutionStatus> statuses =
                new HashMap<>();

        for (TaskExecution taskExecution : taskExecutions) {
            statuses.put(
                    taskExecution.getTaskNode().getId(),
                    taskExecution.getStatus()
            );
        }

        List<Dependency> dependencies =
                dependencyRepository
                        .findByParent_Id(completedTaskId);

        for (Dependency dependency : dependencies) {

            Long childTaskId =
                    dependency.getChild().getId();

            TaskExecution childExecution =
                    taskExecutionRepository
                            .findByWorkflowExecution_IdAndTaskNode_Id(
                                    workflowExecutionId,
                                    childTaskId
                            )
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Task execution not found for task: "
                                                    + childTaskId
                                    )
                            );

            if (childExecution.getStatus()
                    != TaskExecutionStatus.PENDING) {
                continue;
            }

            boolean runnable =
                    executionRuntimeService.canRun(
                            workflowExecutionId,
                            childTaskId,
                            statuses
                    );

            if (runnable) {
                enqueueTask(childExecution);
                continue;
            }

            if (allParentsTerminal(
                    childTaskId,
                    statuses
            )) {

                markSkipped(childExecution);

                statuses.put(
                        childTaskId,
                        TaskExecutionStatus.SKIPPED
                );

                handleTaskCompletion(
                        workflowExecutionId,
                        childTaskId
                );
            }
        }

        checkWorkflowCompletion(
                workflowExecutionId
        );
    }

    private void enqueueTask(
            TaskExecution taskExecution
    ) {

        int claimed =
                taskExecutionRepository.claimForQueue(
                        taskExecution.getId(),
                        TaskExecutionStatus.PENDING,
                        TaskExecutionStatus.QUEUED
                );

        if (claimed == 0) {
            return;
        }

        taskQueueService.enqueue(
                new TaskMessage(
                        taskExecution
                                .getWorkflowExecution()
                                .getId(),

                        taskExecution.getId(),

                        taskExecution
                                .getTaskNode()
                                .getId()
                )
        );
    }

    private void markSkipped(
            TaskExecution taskExecution
    ) {

        taskExecution.setStatus(
                TaskExecutionStatus.SKIPPED
        );

        taskExecutionRepository.save(
                taskExecution
        );
    }

    private boolean allParentsTerminal(
            Long childTaskId,
            Map<Long, TaskExecutionStatus> statuses
    ) {

        List<Dependency> dependencies =
                dependencyRepository
                        .findByChild_Id(childTaskId);

        if (dependencies.isEmpty()) {
            return true;
        }

        for (Dependency dependency : dependencies) {

            Long parentId =
                    dependency
                            .getParent()
                            .getId();

            TaskExecutionStatus status =
                    statuses.get(parentId);

            if (status == null ||
                    !isTerminal(status)) {
                return false;
            }
        }

        return true;
    }

    private boolean isTerminal(
            TaskExecutionStatus status
    ) {

        return status == TaskExecutionStatus.SUCCESS
                || status == TaskExecutionStatus.FAILED
                || status == TaskExecutionStatus.SKIPPED
                || status == TaskExecutionStatus.TIMEOUT;
    }

    private void checkWorkflowCompletion(
            Long workflowExecutionId
    ) {

        List<TaskExecution> executions =
                taskExecutionRepository
                        .findByWorkflowExecution_Id(
                                workflowExecutionId
                        );

        if (executions.isEmpty()) {
            return;
        }

        boolean allTerminal =
                executions.stream()
                        .allMatch(task ->
                                isTerminal(
                                        task.getStatus()
                                )
                        );

        if (!allTerminal) {
            return;
        }

        WorkflowExecution workflowExecution =
                workflowExecutionRepository
                        .findById(workflowExecutionId)
                        .orElseThrow();

        boolean failed =
                executions.stream()
                        .anyMatch(task ->
                                task.getStatus()
                                        == TaskExecutionStatus.FAILED
                                        || task.getStatus()
                                        == TaskExecutionStatus.TIMEOUT
                        );

        workflowExecution.setStatus(
                failed
                        ? WorkflowExecutionStatus.FAILED
                        : WorkflowExecutionStatus.SUCCESS
        );

        workflowExecutionRepository.save(
                workflowExecution
        );
    }
}