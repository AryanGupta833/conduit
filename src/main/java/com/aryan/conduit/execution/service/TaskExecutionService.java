package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskExecutionService {
    private final TaskExecutionRepository taskExecutionRepository;

    @Transactional
    public void markRunning(Long taskExecutionId){
        TaskExecution taskExecution=taskExecutionRepository.findById(taskExecutionId).orElseThrow();

        taskExecution.setStatus(TaskExecutionStatus.RUNNING);
        taskExecutionRepository.save(taskExecution);
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
