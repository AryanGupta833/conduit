package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskExecutionService {
    private final TaskExecutionRepository taskExecutionRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;

    public enum StartDecision { STARTED, DEFERRED, CANCELLED }

    @Transactional
    public StartDecision tryMarkRunning(Long taskExecutionId){
        TaskExecution taskExecution=taskExecutionRepository.findById(taskExecutionId).orElseThrow();
        WorkflowExecution workflowExecution = workflowExecutionRepository
                .findByIdForUpdate(taskExecution.getWorkflowExecution().getId()).orElseThrow();

        if (workflowExecution.getStatus() == WorkflowExecutionStatus.PAUSED
                || workflowExecution.getStatus() == WorkflowExecutionStatus.WAITING_FOR_HUMAN) {
            return StartDecision.DEFERRED;
        }
        if (workflowExecution.getStatus() != WorkflowExecutionStatus.RUNNING) {
            if (taskExecution.getStatus() != TaskExecutionStatus.SUCCESS
                    && taskExecution.getStatus() != TaskExecutionStatus.FAILED
                    && taskExecution.getStatus() != TaskExecutionStatus.TIMEOUT
                    && taskExecution.getStatus() != TaskExecutionStatus.SKIPPED
                    && taskExecution.getStatus() != TaskExecutionStatus.WAITING_FOR_HUMAN) {
                taskExecution.setStatus(TaskExecutionStatus.SKIPPED);
                taskExecutionRepository.save(taskExecution);
            }
            return StartDecision.CANCELLED;
        }

        taskExecution.setStatus(TaskExecutionStatus.RUNNING);
        taskExecutionRepository.save(taskExecution);
        return StartDecision.STARTED;
    }

    @Transactional
    public void markFailed(Long taskExecutionId){
        TaskExecution taskExecution=taskExecutionRepository.findById(taskExecutionId).orElseThrow();

        taskExecution.setStatus(TaskExecutionStatus.FAILED);
        taskExecutionRepository.save(taskExecution);
    }

    @Transactional
    public void markSuccess(Long taskExecutionId){
        TaskExecution taskExecution=taskExecutionRepository.findById(taskExecutionId).orElseThrow();
        taskExecution.setStatus(TaskExecutionStatus.SUCCESS);
        taskExecutionRepository.save(taskExecution);
    }

    @Transactional
    public void markRetrying(Long taskExecutionId){
        TaskExecution taskExecution=taskExecutionRepository.findById(taskExecutionId).orElseThrow();
        taskExecution.setStatus(TaskExecutionStatus.RETRYING);
        taskExecution.setRetryCount(taskExecution.getRetryCount()+1);
        taskExecutionRepository.save(taskExecution);
    }

    @Transactional
    public void markSkipped(Long taskExecutionId){
        TaskExecution taskExecution=taskExecutionRepository.findById(taskExecutionId).orElseThrow();
        taskExecution.setStatus(TaskExecutionStatus.SKIPPED);
        taskExecutionRepository.save(taskExecution);
    }

    @Transactional
    public void markTimeout(Long taskExecutionId){
        TaskExecution taskExecution=taskExecutionRepository.findById(taskExecutionId).orElseThrow();
        taskExecution.setStatus(TaskExecutionStatus.TIMEOUT);
        taskExecutionRepository.save(taskExecution);
    }


}
