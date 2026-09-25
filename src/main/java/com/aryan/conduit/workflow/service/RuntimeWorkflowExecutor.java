package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.service.ExecutionRuntimeService;
import com.aryan.conduit.workflow.dto.RuntimeExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowExecutor {

    private final ExecutionRuntimeService executionRuntimeService;
    private final TaskExecutionRepository taskExecutionRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final TaskQueueService taskQueueService;

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

        /*
         * Dispatch currently ready tasks.
         *
         * IMPORTANT:
         * We do NOT execute the task here anymore.
         * The worker is responsible for execution.
         */
        while (!context.getReadyQueue().isEmpty()) {

            workflowExecution =
                    workflowExecutionRepository
                            .findById(workflowExecutionId)
                            .orElseThrow();

            if (
                    workflowExecution.getStatus()
                            == WorkflowExecutionStatus.PAUSED
            ) {

                System.out.println(
                        "Workflow "
                                + workflowExecutionId
                                + " is paused"
                );

                return;
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
             * A task may have been skipped while evaluating
             * conditional branches.
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

                continue;
            }

            /*
             * Move task into QUEUED state before putting it
             * onto Redis.
             */
            taskExecution.setStatus(
                    TaskExecutionStatus.QUEUED
            );

            taskExecutionRepository.save(
                    taskExecution
            );

            /*
             * Dispatch task to Redis.
             */
            taskQueueService.enqueue(
                    new TaskMessage(
                            workflowExecutionId,
                            taskExecution.getId(),
                            taskId
                    )
            );

            System.out.println(
                    "Task "
                            + taskId
                            + " queued for execution"
            );

            /*
             * IMPORTANT:
             *
             * Do NOT mark the task SUCCESS here.
             *
             * The worker will execute the task and
             * TaskResultHandler will update its final state.
             */
        }

        /*
         * The dispatcher must NOT finalize the workflow here.
         *
         * Tasks may still be:
         *
         * QUEUED
         * RUNNING
         * RETRYING
         *
         * Their results will arrive asynchronously.
         */
        System.out.println(
                "Finished dispatching tasks for workflow "
                        + workflowExecutionId
        );
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
}