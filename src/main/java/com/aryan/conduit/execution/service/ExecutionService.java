package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.dto.ExecutionPlan;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.service.WorkflowGraphService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ExecutionService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowGraphService workflowGraphService;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskNodeRepository taskNodeRepository;

    public Long startWorkflow(Long workflowId){
        Workflow workflow=workflowRepository.findById(workflowId).orElseThrow(()->new RuntimeException("Workflow not found"));

        ExecutionPlan plan=workflowGraphService.generateExecutionPlan(workflowId);

        WorkflowExecution workflowExecution=WorkflowExecution.builder().
                workflow(workflow).
                status(WorkflowExecutionStatus.RUNNING).startedAt(LocalDateTime.now()).
                build();

        workflowExecution=workflowExecutionRepository.save(workflowExecution);

        for(Long taskId:plan.executionOrder()){
            TaskNode taskNode= taskNodeRepository.findById(taskId).orElseThrow(()->new RuntimeException("Task not found"));

            TaskExecution taskExecution=TaskExecution.builder().
                    workflowExecution(workflowExecution).
                    taskNode(taskNode).
                    status(TaskExecutionStatus.PENDING).
                    retryCount(0).build();

            taskExecutionRepository.save(taskExecution);
        }

        List<TaskExecution> taskExecutions=taskExecutionRepository.findByWorkflowExecution_Id(workflowExecution.getId());

        try{
            for(TaskExecution taskExecution:taskExecutions){
                executeTask(taskExecution);
            }
            workflowExecution.setStatus(WorkflowExecutionStatus.SUCCESS);
        }
        catch (Exception e){
            workflowExecution.setStatus(WorkflowExecutionStatus.FAILED);
        }
        workflowExecution.setFinishedAt(LocalDateTime.now());
        workflowExecutionRepository.save(workflowExecution);
        return workflowExecution.getId();

    }

    private void executeTask(TaskExecution taskExecution) throws InterruptedException{
        taskExecution.setStatus(TaskExecutionStatus.RUNNING);
        taskExecutionRepository.save(taskExecution);

        System.out.println("Executing task :"+taskExecution.getTaskNode().getName());

        Thread.sleep(1000);
        taskExecution.setStatus(TaskExecutionStatus.SUCCESS);
        taskExecutionRepository.save(taskExecution);
        System.out.println("Completed task :"+taskExecution.getTaskNode().getName());
    }
}
