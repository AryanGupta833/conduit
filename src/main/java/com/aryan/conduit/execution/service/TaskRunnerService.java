package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.*;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.dto.TaskFuture;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
@RequiredArgsConstructor
public class TaskRunnerService {

    private final TaskExecutionRepository taskExecutionRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final TaskExecutionService taskExecutionService;
    private final ExecutionLogService executionLogService;
    private final TaskOutputService taskOutputService;



    private Map<String,Object> executeTask(Long taskExecutionId)
            throws InterruptedException {
        executionLogService.log(taskExecutionId, LogLevel.INFO,"Task Started");
        taskExecutionService.markRunning(taskExecutionId);
        System.out.println(Thread.currentThread().getName()+
                " executing task "+taskExecutionId);
        Thread.sleep(15000);
        if(taskExecutionId%2==0){
            throw new RuntimeException("Simulated Task Failure");
        }
        executionLogService.log(taskExecutionId,LogLevel.INFO,"Task Completed Successfully");
        taskExecutionService.markSuccess(taskExecutionId);
        Map<String,Object> output=new HashMap<>();
        output.put("prediction","BUY");
        System.out.println(Thread.currentThread().getName()+
                " completed task "+taskExecutionId);
        return output;
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
                        "Starting Stage : " + stage);

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
                                            maxRetries
                                    );

                                }
                                catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    System.out.println("Task "+taskExecutionId+" interrupted");
                                }

                                return null;
                            });

                    futures.add(
                            new TaskFuture(
                                    future,
                                    taskExecutionId,
                                    timeoutSeconds
                            )
                    );
                }

                for (TaskFuture taskFuture : futures) {

                    try {

                        Integer timeout =
                                taskFuture.timeoutSeconds();

                        if(timeout == null || timeout <= 0){
                            timeout = 30;
                        }

                        taskFuture.future().get(
                                timeout,
                                java.util.concurrent.TimeUnit.SECONDS
                        );

                    }
                    catch (java.util.concurrent.TimeoutException e) {
                        executionLogService.log(taskFuture.taskExecutionId(),LogLevel.ERROR,"Task Timed Out");

                        taskExecutionService.markTimeout(
                                taskFuture.taskExecutionId()
                        );

                        taskFuture.future().cancel(true);

                        System.out.println(
                                "Task "
                                        + taskFuture.taskExecutionId()
                                        + " timed out"
                        );

                        throw new RuntimeException(
                                "Task Timeout"
                        );
                    }
                }

                System.out.println(
                        "Completed Stage : " + stage);
            }

            workflowExecution.setStatus(
                    WorkflowExecutionStatus.SUCCESS);

        }
        catch (Exception e) {

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
                    WorkflowExecutionStatus.FAILED);

            throw new RuntimeException(e);

        }
        finally {

            workflowExecution.setFinishedAt(
                    LocalDateTime.now());

            workflowExecutionRepository.save(
                    workflowExecution);

            executorService.shutdown();
        }
    }

    public void executeWithRetry(
            Long taskExecutionId,
            Integer maxRetries)
            throws InterruptedException {

        if(maxRetries == null || maxRetries <= 0){
            maxRetries = 1;
        }

        int attempts = 0;

        while(attempts < maxRetries){

            try{

                Map<String,Object> output=executeTask(taskExecutionId);
                TaskExecution taskExecution=taskExecutionRepository.findById(taskExecutionId).orElseThrow();

                Long workflowExecutionId=taskExecution.getWorkflowExecution().getId();
                taskOutputService.storeOutput(workflowExecutionId,taskExecution.getTaskNode().getId(),output);


                return;
            }
            catch (InterruptedException e){

                System.out.println(
                        "Task "
                                + taskExecutionId
                                + " interrupted due to timeout"
                );

                Thread.currentThread().interrupt();

                throw e;
            }
            catch (Exception e){

                attempts++;
                executionLogService.log(taskExecutionId,LogLevel.WARN,"Retry Attempt "+attempts);

                if(attempts >= maxRetries){

                    executionLogService.log(taskExecutionId,LogLevel.ERROR,"Task Failed");

                    taskExecutionService
                            .markFailed(taskExecutionId);

                    System.out.println(
                            "Task "
                                    + taskExecutionId
                                    + " Failed"
                    );

                    throw e;
                }

                System.out.println(
                        "Retry "
                                + attempts
                                + " for task "
                                + taskExecutionId
                );

                taskExecutionService
                        .markRetrying(taskExecutionId);

                Thread.sleep(2000);
            }
        }
    }

    private void skipRemainingStages(int currentStageIndex,List<List<Long>> stages,Map<Long,TaskExecution> taskExecutionMap){
        for(int i=currentStageIndex+1;i<stages.size();i++){
            List<Long> stage=stages.get(i);

            for(Long taskId:stage){
                Long taskExecutionId=taskExecutionMap.get(taskId).getId();
                executionLogService.log(taskExecutionId,LogLevel.WARN,"Task Skipped");
                taskExecutionService.markSkipped(taskExecutionId);
                System.out.println("Skipped task "+taskExecutionId);
            }
        }
    }

}