package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.service.ExecutionRuntimeService;
import com.aryan.conduit.execution.service.TaskDispatchService;
import com.aryan.conduit.workflow.dto.RuntimeExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeWorkflowExecutor {

    private final ExecutionRuntimeService executionRuntimeService;
    private final TaskExecutionRepository taskExecutionRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final TaskDispatchService taskDispatchService;

    public void execute(Long workflowExecutionId) {

        log.debug("Dispatching tasks for workflow execution {}", workflowExecutionId);

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

                log.debug("Workflow execution {} is paused", workflowExecutionId);

                return;
            }

            if (
                    workflowExecution.getStatus()
                            == WorkflowExecutionStatus.CANCELLED
            ) {

                log.debug("Workflow execution {} was cancelled", workflowExecutionId);

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

            log.debug("Dispatching task node {} for workflow execution {}", taskId, workflowExecutionId);

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

                log.debug("Task node {} is skipped", taskId);

                continue;
            }

            /*
             * Move task into QUEUED state before putting it
             * onto Redis.
             */
            taskDispatchService.dispatch(
                    taskExecution
            );

            log.debug("Task node {} queued for execution", taskId);

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
        log.debug("Finished dispatching tasks for workflow execution {}", workflowExecutionId);
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

            log.debug("Persisted task {} as skipped", taskExecution.getTaskNode().getId());
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

                log.debug("Marked task {} as skipped", task.getTaskNode().getId());
            }
        }
    }
}
