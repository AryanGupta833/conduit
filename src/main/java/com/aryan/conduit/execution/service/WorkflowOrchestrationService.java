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
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkflowOrchestrationService {

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

        List<TaskExecution> taskExecutions =
                taskExecutionRepository
                        .findByWorkflowExecution_Id(
                                workflowExecutionId
                        );

        Map<Long, TaskExecutionStatus> taskStatuses =
                buildTaskStatusMap(taskExecutions);

        List<Dependency> dependencies =
                dependencyRepository.findByParent_Id(
                        completedTaskId
                );

        for (Dependency dependency : dependencies) {

            TaskNode child =
                    dependency.getChild();

            Long childTaskId =
                    child.getId();

            Optional<TaskExecution> existingChild =
                    taskExecutionRepository
                            .findByWorkflowExecution_IdAndTaskNode_Id(
                                    workflowExecutionId,
                                    childTaskId
                            );

            if (existingChild.isPresent()) {
                TaskExecution taskExecution =
                        existingChild.get();

                if (taskExecution.getStatus()
                        == TaskExecutionStatus.SUCCESS
                        || taskExecution.getStatus()
                        == TaskExecutionStatus.RUNNING
                        || taskExecution.getStatus()
                        == TaskExecutionStatus.QUEUED
                        || taskExecution.getStatus()
                        == TaskExecutionStatus.RETRYING) {

                    continue;
                }

                if (taskExecution.getStatus()
                        == TaskExecutionStatus.SKIPPED) {

                    continue;
                }
            }

            boolean canRun =
                    executionRuntimeService.canRun(
                            workflowExecutionId,
                            childTaskId,
                            taskStatuses
                    );

            if (canRun) {

                TaskExecution taskExecution =
                        existingChild.orElseGet(() ->
                                createTaskExecution(
                                        workflowExecution,
                                        child
                                )
                        );

                taskExecution.setStatus(
                        TaskExecutionStatus.QUEUED
                );

                taskExecutionRepository.save(
                        taskExecution
                );

                taskStatuses.put(
                        childTaskId,
                        TaskExecutionStatus.QUEUED
                );

                enqueueAfterCommit(
                        new TaskMessage(
                                workflowExecutionId,
                                taskExecution.getId(),
                                childTaskId
                        )
                );

            } else if (allParentsTerminal(
                    childTaskId,
                    taskStatuses
            )) {

                TaskExecution taskExecution =
                        existingChild.orElseGet(() ->
                                createTaskExecution(
                                        workflowExecution,
                                        child
                                )
                        );

                taskExecution.setStatus(
                        TaskExecutionStatus.SKIPPED
                );

                taskExecutionRepository.save(
                        taskExecution
                );

                taskStatuses.put(
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
                workflowExecution,
                taskExecutions
        );
    }

    private Map<Long, TaskExecutionStatus> buildTaskStatusMap(
            List<TaskExecution> taskExecutions
    ) {

        Map<Long, TaskExecutionStatus> statuses =
                new HashMap<>();

        for (TaskExecution taskExecution : taskExecutions) {

            statuses.put(
                    taskExecution.getTaskNode().getId(),
                    taskExecution.getStatus()
            );
        }

        return statuses;
    }

    private boolean allParentsTerminal(
            Long childTaskId,
            Map<Long, TaskExecutionStatus> taskStatuses
    ) {

        List<Dependency> dependencies =
                dependencyRepository.findByChild_Id(
                        childTaskId
                );

        if (dependencies.isEmpty()) {
            return true;
        }

        for (Dependency dependency : dependencies) {

            Long parentId =
                    dependency.getParent().getId();

            TaskExecutionStatus status =
                    taskStatuses.get(parentId);

            if (status == null
                    || !isTerminal(status)) {

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

    private TaskExecution createTaskExecution(
            WorkflowExecution workflowExecution,
            TaskNode taskNode
    ) {

        return TaskExecution.builder()
                .workflowExecution(workflowExecution)
                .taskNode(taskNode)
                .status(TaskExecutionStatus.PENDING)
                .retryCount(0)
                .idempotencyKey(
                        workflowExecution.getId()
                                + ":"
                                + taskNode.getId()
                )
                .build();
    }

    private void enqueueAfterCommit(
            TaskMessage message
    ) {

        if (TransactionSynchronizationManager
                .isSynchronizationActive()) {

            TransactionSynchronizationManager
                    .registerSynchronization(
                            new TransactionSynchronization() {

                                @Override
                                public void afterCommit() {
                                    taskQueueService.enqueue(
                                            message
                                    );
                                }
                            }
                    );

        } else {

            taskQueueService.enqueue(message);
        }
    }

    private void checkWorkflowCompletion(
            WorkflowExecution workflowExecution,
            List<TaskExecution> taskExecutions
    ) {

        List<TaskExecution> currentTasks =
                taskExecutionRepository
                        .findByWorkflowExecution_Id(
                                workflowExecution.getId()
                        );

        if (currentTasks.isEmpty()) {
            return;
        }

        boolean allTerminal =
                currentTasks.stream()
                        .allMatch(task ->
                                isTerminal(
                                        task.getStatus()
                                )
                        );

        if (!allTerminal) {
            return;
        }

        boolean anyFailed =
                currentTasks.stream()
                        .anyMatch(task ->
                                task.getStatus()
                                        == TaskExecutionStatus.FAILED
                                        || task.getStatus()
                                        == TaskExecutionStatus.TIMEOUT
                        );

        if (anyFailed) {
            workflowExecution.setStatus(
                    WorkflowExecutionStatus.FAILED
            );
        } else {
            workflowExecution.setStatus(
                    WorkflowExecutionStatus.SUCCESS
            );
        }

        workflowExecutionRepository.save(
                workflowExecution
        );
    }
}