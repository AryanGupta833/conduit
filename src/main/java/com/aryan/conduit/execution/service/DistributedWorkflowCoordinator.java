package com.aryan.conduit.execution.service;

import com.aryan.conduit.observability.ConduitMetrics;
import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.service.ExecutionRuntimeService;
import com.aryan.conduit.execution.service.TaskDispatchService;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.aryan.conduit.execution.workspace.ExecutionWorkspaceService;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DistributedWorkflowCoordinator {
    private final ConduitMetrics metrics;

    private final TaskExecutionRepository taskExecutionRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final DependencyRepository dependencyRepository;
    private final ExecutionRuntimeService executionRuntimeService;
    private final TaskDispatchService taskDispatchService;
    private final ExecutionEventService executionEvents;
    private ExecutionWorkspaceService workspaceService;

    private ExecutionNotificationService notificationService;

    @Autowired(required = false)
    void setWorkspaceService(ExecutionWorkspaceService workspaceService) { this.workspaceService = workspaceService; }

    @Autowired(required = false)
    void setNotificationService(ExecutionNotificationService notificationService) { this.notificationService = notificationService; }

    @Transactional
    public void handleTaskCompletion(
            Long workflowExecutionId,
            Long completedTaskId
    ) {

        WorkflowExecution workflowExecution =
                workflowExecutionRepository
                        .findByIdForUpdate(workflowExecutionId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Workflow execution not found: "
                                                + workflowExecutionId
                                )
                        );

        if (workflowExecution.getStatus() != WorkflowExecutionStatus.RUNNING
                && workflowExecution.getStatus() != WorkflowExecutionStatus.WAITING_FOR_HUMAN) {
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

        if (workflowExecution.getStatus() == WorkflowExecutionStatus.WAITING_FOR_HUMAN
                && taskExecutions.stream().noneMatch(task -> task.getStatus() == TaskExecutionStatus.WAITING_FOR_HUMAN)) {
            workflowExecution.setStatus(WorkflowExecutionStatus.RUNNING);
            workflowExecutionRepository.save(workflowExecution);
        }

        processChildren(
                workflowExecutionId,
                completedTaskId,
                statuses
        );

        checkWorkflowCompletion(
                workflowExecutionId
        );
    }

    /**
     * Resume a paused execution and reconcile terminal tasks completed while
     * dispatch was paused. The execution row lock serializes this with task
     * completion and pause/cancel operations across application instances.
     */
    @Transactional
    public void resumeWorkflow(Long workflowExecutionId) {
        WorkflowExecution workflowExecution = workflowExecutionRepository
                .findByIdForUpdate(workflowExecutionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workflow execution not found: " + workflowExecutionId));
        if (workflowExecution.getStatus() != WorkflowExecutionStatus.PAUSED) {
            return;
        }

        workflowExecution.setStatus(WorkflowExecutionStatus.RUNNING);
        workflowExecutionRepository.save(workflowExecution);

        List<TaskExecution> executions = taskExecutionRepository
                .findByWorkflowExecution_Id(workflowExecutionId);
        Map<Long, TaskExecutionStatus> statuses = new HashMap<>();
        for (TaskExecution taskExecution : executions) {
            statuses.put(taskExecution.getTaskNode().getId(), taskExecution.getStatus());
        }

        // The original dispatcher may have observed PAUSED before it queued
        // any roots. Resume must therefore also recover still-pending roots.
        for (TaskExecution taskExecution : executions) {
            Long taskId = taskExecution.getTaskNode().getId();
            if (taskExecution.getStatus() == TaskExecutionStatus.PENDING
                    && dependencyRepository.findByChild_Id(taskId).isEmpty()) {
                if (enqueueTask(taskExecution)) {
                    statuses.put(taskId, TaskExecutionStatus.QUEUED);
                }
            }
        }

        for (TaskExecution taskExecution : executions) {
            if (isTerminal(taskExecution.getStatus())) {
                processChildren(workflowExecutionId, taskExecution.getTaskNode().getId(), statuses);
            }
        }
        checkWorkflowCompletion(workflowExecutionId);
    }

    @Transactional
    public void resumeAfterHumanTask(Long workflowExecutionId, Long completedTaskId) {
        WorkflowExecution execution = workflowExecutionRepository.findByIdForUpdate(workflowExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow execution not found: " + workflowExecutionId));
        if (execution.getStatus() == WorkflowExecutionStatus.CANCELLED || execution.getStatus() == WorkflowExecutionStatus.FAILED
                || execution.getStatus() == WorkflowExecutionStatus.SUCCESS) return;
        execution.setStatus(WorkflowExecutionStatus.RUNNING);
        execution.setFinishedAt(null);
        workflowExecutionRepository.save(execution);
        handleTaskCompletion(workflowExecutionId, completedTaskId);
    }

    private void processChildren(
            Long workflowExecutionId,
            Long parentTaskId,
            Map<Long, TaskExecutionStatus> statuses
    ) {

        List<Dependency> dependencies =
                dependencyRepository.findByParent_Id(
                        parentTaskId
                );

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
                                            "Task execution not found: "
                                                    + childTaskId
                                    )
                            );

            TaskExecutionStatus currentStatus =
                    statuses.get(childTaskId);

            if (currentStatus != null &&
                    currentStatus != TaskExecutionStatus.PENDING) {
                continue;
            }

            boolean runnable =
                    executionRuntimeService.canRun(
                            workflowExecutionId,
                            childTaskId,
                            statuses
                    );

            if (runnable) {

                boolean dispatched =
                        enqueueTask(childExecution);

                if (dispatched) {
                    statuses.put(
                            childTaskId,
                            TaskExecutionStatus.QUEUED
                    );
                }

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

                processChildren(
                        workflowExecutionId,
                        childTaskId,
                        statuses
                );
            }
        }
    }

    private boolean enqueueTask(
            TaskExecution taskExecution
    ) {

        boolean dispatched =
                taskDispatchService.dispatch(
                        taskExecution
                );

        if (dispatched) {

            taskExecution.setStatus(
                    TaskExecutionStatus.QUEUED
            );
        }

        return dispatched;
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
        metrics.conditionalBranchSkipped();
    }

    private boolean allParentsTerminal(
            Long childTaskId,
            Map<Long, TaskExecutionStatus> statuses
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

        if (executions.stream().anyMatch(task -> task.getStatus() == TaskExecutionStatus.WAITING_FOR_HUMAN)) {
            workflowExecutionRepository.findById(workflowExecutionId).ifPresent(execution -> {
                if (execution.getStatus() == WorkflowExecutionStatus.RUNNING) {
                    execution.setStatus(WorkflowExecutionStatus.WAITING_FOR_HUMAN);
                    workflowExecutionRepository.save(execution);
                }
            });
            return;
        }

        boolean allTerminal =
                executions.stream()
                        .allMatch(
                                task ->
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
        boolean wasRunning = workflowExecution.getStatus() == WorkflowExecutionStatus.RUNNING;

        boolean failed =
                executions.stream()
                        .anyMatch(task ->
                                task.getStatus()
                                        == TaskExecutionStatus.FAILED
                                        ||
                                        task.getStatus()
                                                == TaskExecutionStatus.TIMEOUT
                        );

        workflowExecution.setStatus(
                failed
                        ? WorkflowExecutionStatus.FAILED
                        : WorkflowExecutionStatus.SUCCESS
        );
        workflowExecution.setFinishedAt(LocalDateTime.now());

        workflowExecutionRepository.save(
                workflowExecution
        );
        executionEvents.append(workflowExecutionId, null, "EXECUTION_FINISHED",
                workflowExecution.getStatus().name(), null);
        if (failed && notificationService != null) {
            notificationService.publish("execution-failed:" + workflowExecutionId, "EXECUTION_FAILED",
                    "Workflow execution failed", "Execution " + workflowExecutionId + " finished with a failed task.", workflowExecutionId);
        }
        if (workspaceService != null) workspaceService.markFinished(workflowExecutionId.toString(), !failed);
        if (wasRunning) {
            long duration = workflowExecution.getStartedAt() == null ? 0L
                    : Duration.between(workflowExecution.getStartedAt(), workflowExecution.getFinishedAt()).toNanos();
            metrics.workflowFinished(workflowExecution.getStatus().name(), duration);
        }
    }
}
