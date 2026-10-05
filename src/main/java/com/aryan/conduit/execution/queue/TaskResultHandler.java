package com.aryan.conduit.execution.queue;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.service.DistributedWorkflowCoordinator;
import com.aryan.conduit.execution.service.ExecutionContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskResultHandler {

    private final TaskExecutionRepository taskExecutionRepository;
    private final DistributedWorkflowCoordinator coordinator;
    private final ExecutionContextService executionContextService;
    private final com.aryan.conduit.execution.service.ExecutionEventService executionEvents;

    @Autowired
    public TaskResultHandler(TaskExecutionRepository taskExecutionRepository,
                             DistributedWorkflowCoordinator coordinator,
                             ExecutionContextService executionContextService,
                             com.aryan.conduit.execution.service.ExecutionEventService executionEvents) {
        this.taskExecutionRepository = taskExecutionRepository;
        this.coordinator = coordinator;
        this.executionContextService = executionContextService;
        this.executionEvents = executionEvents;
    }

    /** Compatibility constructor for coordinator-only unit tests. */
    public TaskResultHandler(TaskExecutionRepository taskExecutionRepository,
                             DistributedWorkflowCoordinator coordinator) {
        this(taskExecutionRepository, coordinator, null, null);
    }

    public TaskResultHandler(TaskExecutionRepository taskExecutionRepository,
                             DistributedWorkflowCoordinator coordinator,
                             ExecutionContextService executionContextService) {
        this(taskExecutionRepository, coordinator, executionContextService, null);
    }

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

        TaskExecutionStatus nextStatus;
        if (result.timeout()) {

            nextStatus = TaskExecutionStatus.TIMEOUT;

        } else if (result.success()) {

            nextStatus = TaskExecutionStatus.SUCCESS;

        } else {

            nextStatus = TaskExecutionStatus.FAILED;
        }

        var workflowStatus = taskExecution.getWorkflowExecution().getStatus();
        if (workflowStatus == com.aryan.conduit.execution.entity.WorkflowExecutionStatus.TIMED_OUT
                || workflowStatus == com.aryan.conduit.execution.entity.WorkflowExecutionStatus.CANCELLED) {
            TaskExecutionStatus stoppedStatus = workflowStatus == com.aryan.conduit.execution.entity.WorkflowExecutionStatus.TIMED_OUT
                    ? TaskExecutionStatus.TIMEOUT : TaskExecutionStatus.SKIPPED;
            if (!isTerminal(taskExecution.getStatus())) {
                taskExecution.setStatus(stoppedStatus);
                taskExecutionRepository.save(taskExecution);
                if (executionEvents != null) executionEvents.append(taskExecution.getWorkflowExecution().getId(),
                        taskExecution.getId(), "TASK_STOPPED", stoppedStatus.name(), null);
            }
            return; // Never publish a late external result into a stopped execution's context.
        }

        if (!isTerminal(taskExecution.getStatus())) {
            taskExecution.setStatus(nextStatus);
            taskExecutionRepository.save(taskExecution);
            if (executionEvents != null) executionEvents.append(
                    taskExecution.getWorkflowExecution().getId(), taskExecution.getId(),
                    "TASK_COMPLETED", nextStatus.name(), null);
            if (executionContextService != null) executionContextService.commitTaskResult(
                    taskExecution.getWorkflowExecution().getId(),
                    taskExecution.getTaskNode().getId(),
                    taskExecution.getTaskNode().getName(),
                    nextStatus.name(),
                    result.resultData());
        } else if (taskExecution.getStatus() != nextStatus) {
            // A stale/duplicate result must not rewrite an already terminal
            // state (for example TIMEOUT -> FAILED or SUCCESS -> FAILED).
            return;
        }

        coordinator.handleTaskCompletion(
                taskExecution.getWorkflowExecution().getId(),
                taskExecution.getTaskNode().getId()
        );
    }

    /** Replay coordinator work for a terminal task whose previous delivery may
     * have failed after task state was committed but before workflow progress. */
    @Transactional
    public void reconcile(Long taskExecutionId) {
        TaskExecution taskExecution = taskExecutionRepository.findById(taskExecutionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task execution not found: " + taskExecutionId));
        if (!isTerminal(taskExecution.getStatus())) return;
        coordinator.handleTaskCompletion(
                taskExecution.getWorkflowExecution().getId(),
                taskExecution.getTaskNode().getId());
    }

    private boolean isTerminal(TaskExecutionStatus status) {
        return status == TaskExecutionStatus.SUCCESS
                || status == TaskExecutionStatus.FAILED
                || status == TaskExecutionStatus.SKIPPED
                || status == TaskExecutionStatus.TIMEOUT;
    }
}
