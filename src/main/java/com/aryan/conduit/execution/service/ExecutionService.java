package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import com.aryan.conduit.workflow.service.RuntimeWorkflowExecutor;
import com.aryan.conduit.workflow.service.WorkflowExecutionAsyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final ExecutionCreationService executionCreationService;
    private final TaskRunnerService taskRunnerService;
    private final RuntimeWorkflowExecutor runtimeWorkflowExecutor;
    private final WorkflowExecutionAsyncService workflowExecutionAsyncService;
    private final WorkflowExecutionRepository workflowExecutionRepository;



    public Long startWorkflow(Long workflowId){

       if(workflowExecutionRepository.existsByWorkflowVersion_Workflow_IdAndStatus(workflowId, WorkflowExecutionStatus.RUNNING )){
           throw new IllegalStateException("Workflow "+workflowId+" is already running");
       }
       ExecutionContext executionContext=executionCreationService.createExecution(workflowId);
       workflowExecutionAsyncService.executeAsync(executionContext.workflowExecution().getId());

       return executionContext.workflowExecution().getId();
    }

}