package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.ExecutionContextEntity;
import com.aryan.conduit.execution.repository.ExecutionContextRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ExecutionContextServiceTest {
    @Test
    void variableWriteReadsContextUnderDatabaseWriteLockAndPreservesOtherValues() throws Exception {
        ExecutionContextRepository repository = mock(ExecutionContextRepository.class);
        ExecutionContextEntity entity = ExecutionContextEntity.builder()
                .id(1L).variableJson("{\"existing\":\"kept\"}").version(0L).build();
        when(repository.findByWorkflowExecutionIdForUpdate(9L)).thenReturn(Optional.of(entity));

        new ExecutionContextService(repository, new ObjectMapper())
                .putVariable(9L, "customerId", "C123");

        verify(repository).findByWorkflowExecutionIdForUpdate(9L);
        verify(repository, never()).findByWorkflowExecution_Id(9L);
        verify(repository).saveAndFlush(entity);
        var json = new ObjectMapper().readTree(entity.getVariableJson());
        assertTrue(json.path("variables").path("existing").asText().equals("kept"));
        assertTrue(json.path("variables").path("customerId").asText().equals("C123"));
    }

    @Test
    void taskResultIsStructuredAndVariablesAreMerged() throws Exception {
        ExecutionContextRepository repository = mock(ExecutionContextRepository.class);
        ExecutionContextEntity entity = ExecutionContextEntity.builder()
                .id(1L).variableJson("{}").version(0L).build();
        when(repository.findByWorkflowExecutionIdForUpdate(9L)).thenReturn(Optional.of(entity));
        new ExecutionContextService(repository, new ObjectMapper()).commitTaskResult(9L, 12L, "fetchUser", "SUCCESS",
                java.util.Map.of("output", "ok", "variables", java.util.Map.of("userId", 42),
                        "metadata", java.util.Map.of("statusCode", 200)));
        var json = new ObjectMapper().readTree(entity.getVariableJson());
        assertTrue(json.path("tasks").path("12").path("output").asText().equals("ok"));
        assertTrue(json.path("variables").path("userId").asInt() == 42);
        assertTrue(!entity.getVariableJson().contains("must-not-persist"));
    }

    @Test
    void rejectsTaskResultsAboveConfiguredLimit() {
        ExecutionContextRepository repository = mock(ExecutionContextRepository.class);
        ExecutionContextService service = new ExecutionContextService(repository, new ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maximumTaskResultBytes", 128);
        assertThrows(ExecutionContextLimitException.class, () -> service.commitTaskResult(9L, 12L, "large", "SUCCESS",
                java.util.Map.of("output", "x".repeat(1024), "variables", java.util.Map.of())));
        verifyNoInteractions(repository);
    }
}
