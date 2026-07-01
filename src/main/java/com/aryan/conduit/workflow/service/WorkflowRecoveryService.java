package com.aryan.conduit.workflow.service;

import com.aryan.conduit.execution.entity.*;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.service.ExecutionLogService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowRecoveryService {
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final ExecutionLogService executionLogService;

    @PostConstruct
    public void recoverRunningExecutions(){
        List<WorkflowExecution> runningExecutions=workflowExecutionRepository.findByStatus(WorkflowExecutionStatus.RUNNING);

        System.out.println("Recovering "+runningExecutions.size()+" workflows");

        for(WorkflowExecution workflowExecution:runningExecutions){
            System.out.println("Recovering workflow execution "+workflowExecution.getId());
            List<TaskExecution> taskExecutions=taskExecutionRepository.findByWorkflowExecution_Id(workflowExecution.getId());

            for(TaskExecution taskExecution:taskExecutions){
                switch (taskExecution.getStatus()){
                    case RUNNING,RETRYING ->{
                        taskExecution.setStatus(TaskExecutionStatus.FAILED);

                        executionLogService.log(taskExecution.getId(), LogLevel.ERROR,"Recovered after unexpected shutdown");

                    }
                    case PENDING -> {
                        taskExecution.setStatus(TaskExecutionStatus.SKIPPED);
                        executionLogService.log(taskExecution.getId(), LogLevel.WARN,
                                "Skipped during workflow Recovery"
                                );
                    }

                }
            }
            taskExecutionRepository.saveAll(taskExecutions);
            workflowExecution.setStatus(WorkflowExecutionStatus.FAILED);
            workflowExecution.setFinishedAt(LocalDateTime.now());
            workflowExecutionRepository.save(workflowExecution);
            System.out.println("Recovered workflow execution "+workflowExecution.getId());
        }


        System.out.println("Workflow recovery completed");


    }
}
