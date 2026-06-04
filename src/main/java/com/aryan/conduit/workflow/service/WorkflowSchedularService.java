package com.aryan.conduit.workflow.service;


import com.aryan.conduit.execution.service.ExecutionService;
import com.aryan.conduit.workflow.dto.CreateWorkflowRequest;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowSchedularService {
    private final WorkflowRepository workflowRepository;
    private final ExecutionService  executionService;

    @Scheduled(fixedRate = 10000)
    public void checkWorkflows(){
        List<Workflow> workflows=workflowRepository.findByActiveTrue();
        LocalDateTime now=LocalDateTime.now();

        for(Workflow workflow:workflows){
            CronExpression cron= CronExpression.parse(workflow.getCronExpression());
            LocalDateTime baseTime=workflow.getLastScheduledRun()==null?workflow.getCreatedAt():workflow.getLastScheduledRun();

            LocalDateTime nextRun=cron.next(baseTime);
            if(nextRun!=null&&!nextRun.isAfter(now)){
                executionService.startWorkflow(workflow.getId());
                workflow.setLastScheduledRun(nextRun);
                workflowRepository.save(workflow);
            }

        }

    }
}
