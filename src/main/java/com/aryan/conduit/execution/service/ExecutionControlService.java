package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.observability.ConduitMetrics;
import lombok.RequiredArgsConstructor;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionControlService {
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final ConduitMetrics metrics;

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
            execution.setFinishedAt(LocalDateTime.now());
            workflowExecutionRepository.save(execution);
            long duration = execution.getStartedAt() == null ? 0L
                    : Duration.between(execution.getStartedAt(), execution.getFinishedAt()).toNanos();
            metrics.workflowFinished("CANCELLED", duration);
        }
    }
}
