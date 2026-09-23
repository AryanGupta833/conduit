package com.aryan.conduit.workflow.service;


import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.service.ExecutionService;
import com.aryan.conduit.workflow.dto.CreateWorkflowRequest;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowSchedularService {
    private final WorkflowRepository workflowRepository;
    private final ExecutionService  executionService;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final ThreadPoolTaskExecutor workflowExecutor;

    //@Scheduled(fixedRate = 10000)
    public void checkWorkflows(){
        System.out.println(
                "Workflow executor: active=" + workflowExecutor.getActiveCount()
                        + ", pool=" + workflowExecutor.getPoolSize()
                        + ", queue=" + workflowExecutor.getQueueSize()
        );

        List<Workflow> workflows=workflowRepository.findByActiveTrue();
        LocalDateTime now=LocalDateTime.now();

        for(Workflow workflow:workflows){

            if(workflow.getCronExpression()==null||workflow.getCronExpression().isBlank()){
                continue;
            }

            CronExpression cron= CronExpression.parse(workflow.getCronExpression());
            LocalDateTime baseTime=workflow.getLastScheduledRun()==null?workflow.getCreatedAt():workflow.getLastScheduledRun();

            LocalDateTime nextRun=cron.next(baseTime);
            if(nextRun!=null&&!nextRun.isAfter(now)){

                try{

                    workflow.setLastScheduledRun(nextRun);
                    workflowRepository.save(workflow);
                    executionService.startWorkflow(workflow.getId());
                }
                catch (IllegalStateException ex){
                    System.out.println(ex.getMessage());
                }
                catch (Exception ex){
                    System.out.println("Failed to schedule workflow "+workflow.getId()+": "+ex.getMessage());
                }
            }

        }

    }
}
