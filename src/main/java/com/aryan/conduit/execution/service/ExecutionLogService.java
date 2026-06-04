package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.ExecutionLog;
import com.aryan.conduit.execution.entity.LogLevel;
import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.repository.ExecutionLogRepository;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ExecutionLogService {

    private final ExecutionLogRepository executionLogRepository;
    private final TaskExecutionRepository taskExecutionRepository;

    @Transactional
    public void log(Long taskExecutionId, LogLevel level,String message){
        TaskExecution taskExecution=taskExecutionRepository.findById(taskExecutionId).orElseThrow();
        ExecutionLog log=ExecutionLog.builder().level(level).message(message).taskExecution(taskExecution).timestamp(LocalDateTime.now()).build();
        executionLogRepository.save(log);
    }

}
