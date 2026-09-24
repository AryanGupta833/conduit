package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.service.ExecutionRuntimeService;
import com.aryan.conduit.execution.service.TaskRunnerService;
import com.aryan.conduit.workflow.dto.RuntimeExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowExecutor {

    private final ExecutionRuntimeService executionRuntimeService;
    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskRunnerService taskRunnerService;
    private final WorkflowExecutionRepository workflowExecutionRepository;

    public void simulate(Long workflowVersionId) {

        RuntimeExecutionContext context =
                executionRuntimeService.initializeContext(workflowVersionId);

        while (!context.getReadyQueue().isEmpty()) {

            Long taskId =
                    context.getReadyQueue().poll();

            System.out.println("Executing " + taskId);

            System.out.println(
                    "Finished task " + taskId +
                            " evaluating children"
            );

            executionRuntimeService.evaluateChildren(
                    workflowVersionId,
                    taskId,
                    context
            );
        }
    }

    public void execute(Long workflowExecutionId) {

        System.out.println(
                "RuntimeWorkflowExecutor started "
                        + workflowExecutionId
        );

        WorkflowExecution workflowExecution =
                workflowExecutionRepository
                        .findById(workflowExecutionId)
                        .orElseThrow();

        Long workflowVersionId =
                workflowExecution
                        .getWorkflowVersion()
                        .getId();

        RuntimeExecutionContext context =
                executionRuntimeService.initializeContext(
                        workflowVersionId
                );

        while (!context.getReadyQueue().isEmpty()) {

            workflowExecution =
                    workflowExecutionRepository
                            .findById(workflowExecutionId)
                            .orElseThrow();

            while (
                    workflowExecution.getStatus()
                            == WorkflowExecutionStatus.PAUSED
            ) {

                System.out.println(
                        "Workflow "
                                + workflowExecutionId
                                + " is paused"
                );

                try {

                    Thread.sleep(1000);

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();
                    return;
                }

                workflowExecution =
                        workflowExecutionRepository
                                .findById(workflowExecutionId)
                                .orElseThrow();
            }

            if (
                    workflowExecution.getStatus()
                            == WorkflowExecutionStatus.CANCELLED
            ) {

                System.out.println(
                        "Workflow "
                                + workflowExecutionId
                                + " cancelled"
                );

                markRemainingTasksSkipped(
                        workflowExecutionId
                );

                workflowExecution.setFinishedAt(
                        LocalDateTime.now()
                );

                workflowExecutionRepository.save(
                        workflowExecution
                );

                return;
            }

            Long taskId =
                    context.getReadyQueue().poll();

            if (taskId == null) {
                break;
            }

            System.out.println(
                    "Dequeued task " + taskId
            );

            TaskExecution taskExecution =
                    taskExecutionRepository
                            .findByWorkflowExecution_IdAndTaskNode_Id(
                                    workflowExecutionId,
                                    taskId
                            )
                            .orElseThrow();

            /*
             * A task can be marked SKIPPED by
             * ExecutionRuntimeService while evaluating
             * conditional branches.
             *
             * Never execute a task that is already skipped.
             */
            if (
                    context.getTaskStatuses().get(taskId)
                            == TaskExecutionStatus.SKIPPED
            ) {

                persistSkippedTask(
                        taskExecution,
                        context
                );

                System.out.println(
                        "Task "
                                + taskId
                                + " is SKIPPED"
                );

                executionRuntimeService.evaluateChildren(
                        workflowExecutionId,
                        taskId,
                        context
                );

                continue;
            }

            try {

                Map<String, Object> taskResult =
                        taskRunnerService.executeWithRetry(
                                taskExecution.getId(),
                                taskExecution
                                        .getTaskNode()
                                        .getMaxRetries(),
                                taskExecution
                                        .getTaskNode()
                                        .getTimeoutSeconds()
                        );

                System.out.println(
                        "executeWithRetry returned for task "
                                + taskId
                );

                /*
                 * Update runtime state.
                 */
                context.getTaskStatuses().put(
                        taskId,
                        TaskExecutionStatus.SUCCESS
                );

                /*
                 * Persist execution state.
                 *
                 * This is important because markRemainingTasksSkipped()
                 * checks the database status. Without this update,
                 * a successfully executed task remains PENDING in the
                 * database and can incorrectly be marked SKIPPED later.
                 */
                taskExecution.setStatus(
                        TaskExecutionStatus.SUCCESS
                );

                taskExecutionRepository.save(
                        taskExecution
                );

                System.out.println(
                        "Task "
                                + taskId
                                + " marked SUCCESS"
                );

            } catch (Exception e) {

                System.out.println(
                        "Task "
                                + taskId
                                + " failed with "
                                + e.getClass().getSimpleName()
                                + " : "
                                + e.getMessage()
                );

                /*
                 * Update runtime state.
                 */
                context.getTaskStatuses().put(
                        taskId,
                        TaskExecutionStatus.FAILED
                );

                /*
                 * Persist execution state.
                 */
                taskExecution.setStatus(
                        TaskExecutionStatus.FAILED
                );

                taskExecutionRepository.save(
                        taskExecution
                );

                System.out.println(
                        "Task "
                                + taskId
                                + " marked FAILED"
                );
            }

            System.out.println(
                    "Evaluating children of task "
                            + taskId
            );

            executionRuntimeService.evaluateChildren(
                    workflowExecutionId,
                    taskId,
                    context
            );

            /*
             * Persist any SKIPPED tasks discovered while
             * evaluating this task's children.
             */
            persistRuntimeSkippedTasks(
                    workflowExecutionId,
                    context
            );

            System.out.println(
                    "Current queue = "
                            + context.getReadyQueue()
            );
        }

        System.out.println(
                "Queue empty finalizing workflow "
                        + workflowExecutionId
        );

        System.out.println(
                "Final task statuses = "
                        + context.getTaskStatuses()
        );

        /*
         * Any PENDING tasks left after the runtime queue is
         * exhausted are no longer executable.
         */
        markRemainingTasksSkipped(
                workflowExecutionId
        );

        /*
         * Synchronize runtime SKIPPED states before
         * determining the final workflow status.
         */
        persistRuntimeSkippedTasks(
                workflowExecutionId,
                context
        );

        finalizeWorkflow(
                workflowExecutionId,
                context
        );
    }

    private void persistRuntimeSkippedTasks(
            Long workflowExecutionId,
            RuntimeExecutionContext context
    ) {

        for (
                Map.Entry<Long, TaskExecutionStatus> entry
                : context.getTaskStatuses().entrySet()
        ) {

            Long taskId = entry.getKey();

            TaskExecutionStatus status =
                    entry.getValue();

            if (status != TaskExecutionStatus.SKIPPED) {
                continue;
            }

            taskExecutionRepository
                    .findByWorkflowExecution_IdAndTaskNode_Id(
                            workflowExecutionId,
                            taskId
                    )
                    .ifPresent(taskExecution ->
                            persistSkippedTask(
                                    taskExecution,
                                    context
                            )
                    );
        }
    }

    private void persistSkippedTask(
            TaskExecution taskExecution,
            RuntimeExecutionContext context
    ) {

        if (
                taskExecution.getStatus()
                        != TaskExecutionStatus.SKIPPED
        ) {

            taskExecution.setStatus(
                    TaskExecutionStatus.SKIPPED
            );

            taskExecutionRepository.save(
                    taskExecution
            );

            System.out.println(
                    "Persisted task "
                            + taskExecution
                            .getTaskNode()
                            .getId()
                            + " as SKIPPED"
            );
        }

        context.getTaskStatuses().put(
                taskExecution
                        .getTaskNode()
                        .getId(),
                TaskExecutionStatus.SKIPPED
        );
    }

    private void markRemainingTasksSkipped(
            Long workflowExecutionId
    ) {

        List<TaskExecution> tasks =
                taskExecutionRepository
                        .findByWorkflowExecution_Id(
                                workflowExecutionId
                        );

        for (TaskExecution task : tasks) {

            if (
                    task.getStatus()
                            == TaskExecutionStatus.PENDING
            ) {

                task.setStatus(
                        TaskExecutionStatus.SKIPPED
                );

                taskExecutionRepository.save(
                        task
                );

                System.out.println(
                        "Marked task "
                                + task.getTaskNode().getId()
                                + " as SKIPPED"
                );
            }
        }
    }

    private void finalizeWorkflow(
            Long workflowExecutionId,
            RuntimeExecutionContext context
    ) {

        WorkflowExecution workflowExecution =
                workflowExecutionRepository
                        .findById(workflowExecutionId)
                        .orElseThrow();

        boolean hasFailure =
                context.getTaskStatuses()
                        .values()
                        .stream()
                        .anyMatch(
                                status ->
                                        status
                                                == TaskExecutionStatus.FAILED
                                                || status
                                                == TaskExecutionStatus.TIMEOUT
                        );

        if (hasFailure) {

            workflowExecution.setStatus(
                    WorkflowExecutionStatus.FAILED
            );

            System.out.println(
                    "Setting workflow to FAILED"
            );

        } else {

            workflowExecution.setStatus(
                    WorkflowExecutionStatus.SUCCESS
            );

            System.out.println(
                    "Setting workflow to SUCCESS"
            );
        }

        workflowExecution.setFinishedAt(
                LocalDateTime.now()
        );

        workflowExecutionRepository.save(
                workflowExecution
        );

        System.out.println(
                "Workflow execution "
                        + workflowExecutionId
                        + " saved successfully"
        );
    }
}