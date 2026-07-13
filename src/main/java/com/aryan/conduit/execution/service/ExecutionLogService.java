package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.ExecutionLog;
import com.aryan.conduit.execution.entity.LogLevel;
import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.repository.ExecutionLogRepository;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.workflow.dto.ExecutionLogResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ExecutionLogService {

    private final ExecutionLogRepository executionLogRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final ExecutionLogStreamService executionLogStreamService;


    @Transactional
    public void log(Long taskExecutionId, LogLevel level, String message) {

        TaskExecution taskExecution =
                taskExecutionRepository
                        .findById(taskExecutionId)
                        .orElseThrow();

        ExecutionLog log =
                ExecutionLog.builder()
                        .taskExecution(taskExecution)
                        .level(level)
                        .message(message)
                        .timestamp(LocalDateTime.now())
                        .build();

        executionLogRepository.save(log);

        Long executionId =
                taskExecution
                        .getWorkflowExecution()
                        .getId();

        executionLogStreamService.send(executionId,new ExecutionLogResponse(log.getId(),log.getLevel(),log.getMessage(),log.getTimestamp()));
    }

}
