package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.*;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.retry.RetryPolicy;
import com.aryan.conduit.execution.retry.RetryPolicyFactory;
import com.aryan.conduit.execution.retry.TaskTimeoutException;
import com.aryan.conduit.plugin.PluginManager;
import com.aryan.conduit.workflow.dto.TaskFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import com.aryan.conduit.plugin.PluginResult;
import com.aryan.conduit.plugin.WorkflowPlugin;

@Service
@RequiredArgsConstructor
public class TaskRunnerService {

    private final TaskExecutionRepository taskExecutionRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final TaskExecutionService taskExecutionService;
    private final ExecutionLogService executionLogService;
    private final TaskOutputService taskOutputService;
    private final CircuitBreakerTaskService circuitBreakerTaskService;
    private final PluginManager pluginManager;
    private final RetryPolicyFactory retryPolicyFactory;
    private final IdempotencyService idempotencyService;


    private Map<String, Object> executeTask(Long taskExecutionId)
            throws InterruptedException {

        return executeTaskInternal(taskExecutionId);

    }


    private Map<String, Object> executeTaskInternal(
            Long taskExecutionId)
            throws InterruptedException {

        executionLogService.log(
                taskExecutionId,
                LogLevel.INFO,
                "Task Started"
        );

        TaskExecution taskExecution =
                taskExecutionRepository.findById(taskExecutionId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Task execution not found: "
                                                + taskExecutionId
                                ));

        String idempotencyKey =
                taskExecution.getIdempotencyKey();

        /*
         * Check whether this logical task has already
         * completed successfully.
         */
        var completedResult =
                idempotencyService.getCompletedResult(idempotencyKey);


        if (completedResult.isPresent()) {

            executionLogService.log(
                    taskExecutionId,
                    LogLevel.INFO,
                    "Returning previously completed idempotent result"
            );

            return pluginResultToMap(completedResult.get());
        }

        /*
         * Try to acquire ownership of this logical task.
         *
         * The UNIQUE constraint on idempotency_key ensures
         * that only one worker can acquire it.
         */
        boolean acquired =
                idempotencyService.tryStart(idempotencyKey);

        if (!acquired) {

            /*
             * Another worker may have completed the task
             * between our first check and tryStart().
             */
            completedResult =
                    idempotencyService.getCompletedResult(idempotencyKey);

            if (completedResult.isPresent()) {

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.INFO,
                        "Another execution completed this task; "
                                + "using stored idempotent result"
                );

                return pluginResultToMap(completedResult.get());
            }

            /*
             * The task is currently being executed by
             * another worker.
             */
            throw new IllegalStateException(
                    "Task is already being executed: "
                            + idempotencyKey
            );
        }

        /*
         * Idempotency ownership was successfully acquired.
         * Only now should the TaskExecution become RUNNING.
         */
        taskExecutionService.markRunning(taskExecutionId);

        try {

            String pluginType =
                    taskExecution.getTaskNode().getPluginType();

            WorkflowPlugin plugin =
                    pluginManager.getPlugin(pluginType);

            executionLogService.log(
                    taskExecutionId,
                    LogLevel.INFO,
                    "Executing plugin: " + pluginType
            );

            /*
             * Variables will later come from the workflow
             * execution context / expression engine.
             */
            Map<String, Object> variables =
                    new HashMap<>();

            PluginResult result =
                    plugin.execute(
                            taskExecution.getTaskNode(),
                            variables
                    );

            /*
             * Plugin itself reported failure.
             */
            if (!result.isSuccess()) {

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.ERROR,
                        "Plugin execution failed: "
                                + result.getOutput()
                );

                /*
                 * Release the idempotency lock so that
                 * the retry mechanism can execute the task again.
                 */
                idempotencyService.release(idempotencyKey);

                throw new RuntimeException(
                        result.getOutput()
                );
            }

            /*
             * Plugin completed successfully.
             *
             * Store the result before marking the task
             * as successfully completed.
             */
            idempotencyService.complete(
                    idempotencyKey,
                    result
            );

            executionLogService.log(
                    taskExecutionId,
                    LogLevel.INFO,
                    "Task Completed Successfully"
            );

            taskExecutionService.markSuccess(taskExecutionId);

            return pluginResultToMap(result);

        }

         catch (Exception e) {

            /*
             * Plugin threw an exception.
             *
             * Release the idempotency ownership so the
             * existing retry mechanism can attempt it again.
             */
            idempotencyService.release(idempotencyKey);

            executionLogService.log(
                    taskExecutionId,
                    LogLevel.ERROR,
                    "Plugin execution failed: "
                            + e.getMessage()
            );

            throw e;
        }
    }


    public void runExecution(
            WorkflowExecution workflowExecution,
            List<List<Long>> stages,
            Map<Long, TaskExecution> taskExecutionMap) {

        ExecutorService executorService =
                Executors.newFixedThreadPool(4);

        int currentStageIndex = -1;

        try {

            for (int stageIndex = 0;
                 stageIndex < stages.size();
                 stageIndex++) {

                currentStageIndex = stageIndex;

                List<Long> stage =
                        stages.get(stageIndex);

                System.out.println(
                        "Starting Stage : "
                                + stage
                );

                List<TaskFuture> futures =
                        new ArrayList<>();


                for (Long taskId : stage) {

                    TaskExecution taskExecution =
                            taskExecutionMap.get(taskId);

                    Long taskExecutionId =
                            taskExecution.getId();

                    Integer maxRetries =
                            taskExecution
                                    .getTaskNode()
                                    .getMaxRetries();

                    Integer timeoutSeconds =
                            taskExecution
                                    .getTaskNode()
                                    .getTimeoutSeconds();


                    Future<?> future =
                            executorService.submit(() -> {

                                try {

                                    executeWithRetry(
                                            taskExecutionId,
                                            maxRetries,
                                            timeoutSeconds
                                    );

                                } catch (InterruptedException e) {

                                    Thread.currentThread()
                                            .interrupt();

                                    executionLogService.log(
                                            taskExecutionId,
                                            LogLevel.ERROR,
                                            "Task interrupted"
                                    );

                                    throw new RuntimeException(e);
                                }

                                return null;
                            });


                    futures.add(
                            new TaskFuture(
                                    future,
                                    taskExecutionId,
                                    null
                            )
                    );
                }


                /*
                 * We no longer apply the timeout here.
                 *
                 * Timeout is handled inside each retry attempt.
                 */
                for (TaskFuture taskFuture : futures) {

                    try {

                        taskFuture.future().get();

                    } catch (ExecutionException e) {

                        throw new RuntimeException(
                                e.getCause()
                        );

                    } catch (InterruptedException e) {

                        Thread.currentThread()
                                .interrupt();

                        throw e;
                    }
                }


                System.out.println(
                        "Completed Stage : "
                                + stage
                );
            }


            workflowExecution.setStatus(
                    WorkflowExecutionStatus.SUCCESS
            );


        } catch (Exception e) {

            System.out.println(
                    "Workflow Failed : "
                            + e.getMessage()
            );

            skipRemainingStages(
                    currentStageIndex,
                    stages,
                    taskExecutionMap
            );

            workflowExecution.setStatus(
                    WorkflowExecutionStatus.FAILED
            );

            throw new RuntimeException(e);


        } finally {

            workflowExecution.setFinishedAt(
                    LocalDateTime.now()
            );

            workflowExecutionRepository.save(
                    workflowExecution
            );

            executorService.shutdown();
        }
    }


    public void executeWithRetry(
            Long taskExecutionId,
            Integer maxRetries,
            Integer timeoutSeconds)
            throws InterruptedException {


        RetryPolicy policy =
                retryPolicyFactory.defaultPolicy(
                        maxRetries
                );


        int attempt = 0;


        while (true) {

            attempt++;


            try {

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.INFO,
                        "Task attempt "
                                + attempt
                                + " started"
                );


                Map<String, Object> output =
                        executeAttemptWithTimeout(
                                taskExecutionId,
                                timeoutSeconds
                        );


                TaskExecution taskExecution =
                        taskExecutionRepository
                                .findById(taskExecutionId)
                                .orElseThrow();


                Long workflowExecutionId =
                        taskExecution
                                .getWorkflowExecution()
                                .getId();


                taskOutputService.storeOutput(
                        workflowExecutionId,
                        taskExecution
                                .getTaskNode()
                                .getId(),
                        output
                );


                executionLogService.log(
                        taskExecutionId,
                        LogLevel.INFO,
                        "Task completed successfully on attempt "
                                + attempt
                );


                return;


            } catch (TaskTimeoutException e) {

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.ERROR,
                        "Task timed out on attempt "
                                + attempt
                );


                if (!policy.shouldRetry(attempt)) {

                    executionLogService.log(
                            taskExecutionId,
                            LogLevel.ERROR,
                            "Task failed permanently after timeout"
                    );

                    taskExecutionService.markTimeout(taskExecutionId);

                    throw e;
                }

                long delay = policy.calculateDelay(attempt);

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.WARN,
                        "Retrying timed out task. Retry "
                                + attempt
                                + " of "
                                + policy.getMaxRetries()
                                + " after "
                                + delay
                                + " ms"
                );

                taskExecutionService.markRetrying(taskExecutionId);

                Thread.sleep(delay);


            } catch (InterruptedException e) {

                /*
                 * Cancellation/interruption should NOT retry.
                 */
                Thread.currentThread()
                        .interrupt();

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.ERROR,
                        "Task interrupted during attempt "
                                + attempt
                );

                throw e;


            } catch (Exception e) {


                if (!policy.shouldRetry(attempt)) {

                    executionLogService.log(
                            taskExecutionId,
                            LogLevel.ERROR,
                            "Task failed permanently after "
                                    + attempt
                                    + " attempt(s)"
                    );

                    taskExecutionService.markFailed(
                            taskExecutionId
                    );

                    throw e;
                }


                long delay =
                        policy.calculateDelay(attempt);


                executionLogService.log(
                        taskExecutionId,
                        LogLevel.WARN,
                        "Retrying task. Retry "
                                + attempt
                                + " of "
                                + policy.getMaxRetries()
                                + " after "
                                + delay
                                + " ms"
                );


                taskExecutionService.markRetrying(
                        taskExecutionId
                );


                Thread.sleep(delay);
            }
        }
    }


    private Map<String, Object> executeAttemptWithTimeout(
            Long taskExecutionId,
            Integer timeoutSeconds)
            throws InterruptedException {


        int timeout =
                timeoutSeconds == null || timeoutSeconds <= 0
                        ? 30
                        : timeoutSeconds;


        ExecutorService attemptExecutor =
                Executors.newSingleThreadExecutor();


        Future<Map<String, Object>> future =
                attemptExecutor.submit(
                        () -> executeTask(taskExecutionId)
                );


        try {

            return future.get(
                    timeout,
                    TimeUnit.SECONDS
            );


        } catch (TimeoutException e) {

            future.cancel(true);

            throw new TaskTimeoutException(
                    "Task "
                            + taskExecutionId
                            + " timed out after "
                            + timeout
                            + " seconds"
            );


        } catch (ExecutionException e) {

            Throwable cause =
                    e.getCause();

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw new RuntimeException(cause);


        } finally {

            attemptExecutor.shutdownNow();
        }
    }


    private void skipRemainingStages(
            int currentStageIndex,
            List<List<Long>> stages,
            Map<Long, TaskExecution> taskExecutionMap) {

        for (int i = currentStageIndex + 1;
             i < stages.size();
             i++) {

            List<Long> stage =
                    stages.get(i);

            for (Long taskId : stage) {

                Long taskExecutionId =
                        taskExecutionMap
                                .get(taskId)
                                .getId();

                executionLogService.log(
                        taskExecutionId,
                        LogLevel.WARN,
                        "Task Skipped"
                );

                taskExecutionService.markSkipped(
                        taskExecutionId
                );

                System.out.println(
                        "Skipped task "
                                + taskExecutionId
                );
            }
        }
    }
    private Map<String, Object> pluginResultToMap(
            PluginResult result) {

        Map<String, Object> output = new HashMap<>();

        output.put("success", result.isSuccess());
        output.put("output", result.getOutput());

        if (result.getVariables() != null) {
            output.put("variables", result.getVariables());
        }

        if (result.getMetadata() != null) {
            output.put("metadata", result.getMetadata());
        }

        return output;
    }
}