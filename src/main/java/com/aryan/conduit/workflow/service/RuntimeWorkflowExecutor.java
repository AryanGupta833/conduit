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
import java.util.List;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowExecutor {
    private final ExecutionRuntimeService executionRuntimeService;
    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskRunnerService taskRunnerService;
    private final WorkflowExecutionRepository workflowExecutionRepository;

    public void simulate(Long workflowId){
        RuntimeExecutionContext context= executionRuntimeService.initializeContext(workflowId);

        while (!context.getReadyQueue().isEmpty()){
            Long taskId=context.getReadyQueue().poll();
            System.out.println("Executing "+taskId);
            context.getTaskStatuses().put(taskId, TaskExecutionStatus.SUCCESS);
            System.out.println("finished task "+taskId+",evaluating children");
            executionRuntimeService.evaluateChildren(workflowId,taskId,context);
        }
    }

    public void execute(Long workflowExecutionId,Long workflowId) {
        System.out.println("RuntimeWorkflowExecutor started "+workflowExecutionId);
        RuntimeExecutionContext context= executionRuntimeService.initializeContext(workflowId);
        while (!context.getReadyQueue().isEmpty()){
            Long taskId=context.getReadyQueue().poll();
            System.out.println("Dequeued task "+taskId);
            TaskExecution taskExecution=taskExecutionRepository.findByWorkflowExecution_IdAndTaskNode_Id(workflowExecutionId,taskId).orElseThrow();

            try {
                taskRunnerService.executeWithRetry(taskExecution.getId(), taskExecution.getTaskNode().getMaxRetries());
                context.getTaskStatuses().put(taskId,TaskExecutionStatus.SUCCESS);
                System.out.println("Task "+taskId+" marked SUCCESS");
            }
            catch (Exception e){
                System.out.println("Task "+taskId+" failed with "+e.getClass().getSimpleName()+":"+e.getMessage());
                context.getTaskStatuses().put(taskId,TaskExecutionStatus.FAILED);
                System.out.println("Task "+taskId+" marked FAILED");
            }
            System.out.println("Evaluating children of task "+taskId);
            executionRuntimeService.evaluateChildren(workflowExecutionId,taskId,context);
            System.out.println("Current queue = "+context.getReadyQueue());



        }
        System.out.println(
                "Queue empty, finalizing workflow "
                        + workflowExecutionId
        );

        System.out.println(
                "Final task statuses = "
                        + context.getTaskStatuses()
        );
        markRemainingTasksSkipped(workflowExecutionId);

        finalizeWorkflow(workflowExecutionId,context);
    }

    private void markRemainingTasksSkipped(Long workflowExecutionId){
        List<TaskExecution> tasks=taskExecutionRepository.findByWorkflowExecution_Id(workflowExecutionId);
        for (TaskExecution task:tasks){
            if(task.getStatus()==TaskExecutionStatus.PENDING){
                task.setStatus(TaskExecutionStatus.SKIPPED);
                taskExecutionRepository.save(task);
                System.out.println("Marked task "+task.getTaskNode().getId()+" as SKIPPED");
            }
        }
    }

    private void finalizeWorkflow(Long workflowExecutionId,RuntimeExecutionContext context){
        System.out.println("Finalizing workflow "+workflowExecutionId);
        WorkflowExecution workflowExecution=workflowExecutionRepository.findById(workflowExecutionId).orElseThrow();
        boolean hasFailure=context.getTaskStatuses().values().stream().anyMatch(status ->
                status==TaskExecutionStatus.FAILED);
        System.out.println("Has failure = "+hasFailure);
        if(hasFailure){
            workflowExecution.setStatus(WorkflowExecutionStatus.FAILED);
            System.out.println("Setting workflow to FAILED");

        }
        else {
            workflowExecution.setStatus(WorkflowExecutionStatus.SUCCESS);
            System.out.println("Setting workflow to SUCCESS");
        }

        workflowExecution.setFinishedAt(LocalDateTime.now());
        System.out.println("Saving workflow execution "+workflowExecutionId);
        workflowExecutionRepository.save(workflowExecution);
        System.out.println("Workflow execution "+workflowExecutionId+" saved successfully");
    }

}
