package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionControlService {
    private final WorkflowExecutionRepository workflowExecutionRepository;

    public void pause(Long executionId){
        WorkflowExecution execution=workflowExecutionRepository.findById(executionId).orElseThrow();
        if(execution.getStatus()== WorkflowExecutionStatus.RUNNING){
            execution.setStatus(WorkflowExecutionStatus.PAUSED);
            workflowExecutionRepository.save(execution);
        }
    }

    public void resume(Long executionId){
        WorkflowExecution execution=workflowExecutionRepository.findById(executionId).orElseThrow();
        if(execution.getStatus()==WorkflowExecutionStatus.PAUSED){
            execution.setStatus(WorkflowExecutionStatus.RUNNING);
            workflowExecutionRepository.save(execution);
        }
    }

    public void cancel(Long executionId){
        WorkflowExecution execution=workflowExecutionRepository.findById(executionId).orElseThrow();
        if(execution.getStatus()==WorkflowExecutionStatus.RUNNING||execution.getStatus()==WorkflowExecutionStatus.PAUSED){
            execution.setStatus(WorkflowExecutionStatus.CANCELLED);
            workflowExecutionRepository.save(execution);
        }
    }
}
