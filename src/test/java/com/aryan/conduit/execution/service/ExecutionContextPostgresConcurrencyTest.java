package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.ExecutionContextEntity;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.ExecutionContextRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.entity.WorkflowStatus;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ExecutionContextPostgresConcurrencyTest {
    @Autowired WorkflowRepository workflowRepository;
    @Autowired WorkflowVersionRepository workflowVersionRepository;
    @Autowired WorkflowExecutionRepository workflowExecutionRepository;
    @Autowired ExecutionContextRepository executionContextRepository;
    @Autowired ExecutionContextService contextService;

    @Test
    void parallelWritersMergeAndNewServiceReconstructsPersistedContext() throws Exception {
        Workflow workflow = workflowRepository.saveAndFlush(Workflow.builder()
                .name("context-concurrency-" + System.nanoTime()).status(WorkflowStatus.ACTIVE)
                .active(false).createdAt(LocalDateTime.now()).build());
        WorkflowVersion version = workflowVersionRepository.saveAndFlush(WorkflowVersion.builder()
                .workflow(workflow).versionNumber(1).published(true).latest(true).createdAt(LocalDateTime.now()).build());
        WorkflowExecution execution = workflowExecutionRepository.saveAndFlush(WorkflowExecution.builder()
                .workflowVersion(version).versionNumber(1).status(WorkflowExecutionStatus.RUNNING)
                .startedAt(LocalDateTime.now()).build());
        ExecutionContextEntity persistedContext = executionContextRepository.saveAndFlush(ExecutionContextEntity.builder()
                .workflowExecution(execution).variableJson("{}").build());

        try (var pool = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                final int value = i;
                writes.add(pool.submit(() -> contextService.putVariable(execution.getId(), "parallel_" + value, value)));
            }
            for (var write : writes) write.get(30, TimeUnit.SECONDS);
        }
        contextService.commitTaskResult(execution.getId(), 777L, "priorTask", "SUCCESS",
                Map.of("output", "persisted", "variables", Map.of("customerId", "C123"),
                        "metadata", Map.of("statusCode", 200, "password", "must-not-persist")));

        ExecutionContextService newReader = new ExecutionContextService(executionContextRepository, new ObjectMapper());
        Map<String, Object> variables = newReader.getVariables(execution.getId());
        Map<String, Object> taskResults = newReader.getTaskResults(execution.getId());
        assertEquals(24, variables.keySet().stream().filter(key -> key.startsWith("parallel_")).count());
        assertEquals("C123", variables.get("customerId"));
        assertEquals("persisted", ((Map<?, ?>) taskResults.get("777")).get("output"));
        assertTrue(!((Map<?, ?>) taskResults.get("777")).containsKey("password"));

        executionContextRepository.deleteById(persistedContext.getId());
        workflowExecutionRepository.delete(execution);
        workflowVersionRepository.delete(version);
        workflowRepository.delete(workflow);
    }
}
