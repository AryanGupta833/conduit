package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.ExecutionLog;
import com.aryan.conduit.execution.entity.LogLevel;
import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ExecutionLogPersistenceTest {

    @Autowired TaskExecutionRepository taskExecutionRepository;
    @Autowired ExecutionLogRepository executionLogRepository;

    @Test
    void persistsAndRetrievesMessagesLongerThanVarchar255() {
        String message = "execution-output-" + "x".repeat(2_000);
        TaskExecution task = taskExecutionRepository.saveAndFlush(TaskExecution.builder()
                .status(TaskExecutionStatus.RUNNING)
                .retryCount(0)
                .idempotencyKey("long-execution-log-" + UUID.randomUUID())
                .build());

        ExecutionLog saved = executionLogRepository.saveAndFlush(ExecutionLog.builder()
                .taskExecution(task)
                .level(LogLevel.INFO)
                .message(message)
                .timestamp(java.time.LocalDateTime.now())
                .build());

        assertEquals(message, executionLogRepository.findById(saved.getId()).orElseThrow().getMessage());
    }
}
