package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpressionContextServiceTest {

    private ExecutionContextService executionContextService;
    private TaskOutputService taskOutputService;
    private WorkflowExecutionRepository workflowExecutionRepository;
    private TaskNodeRepository taskNodeRepository;
    private ExpressionContextService expressionContextService;

    @BeforeEach
    void setUp() {

        executionContextService =
                mock(ExecutionContextService.class);

        taskOutputService =
                mock(TaskOutputService.class);

        workflowExecutionRepository =
                mock(WorkflowExecutionRepository.class);

        taskNodeRepository =
                mock(TaskNodeRepository.class);

        expressionContextService =
                new ExpressionContextService(
                        executionContextService,
                        taskOutputService,
                        workflowExecutionRepository,
                        taskNodeRepository,
                        new ObjectMapper()
                );
    }

    @Test
    void shouldNormalizeNestedJsonTaskOutput() {

        Long executionId = 1L;
        Long versionId = 10L;
        Long taskId = 100L;

        WorkflowExecution execution =
                WorkflowExecution.builder()
                        .id(executionId)
                        .workflowVersion(
                                com.aryan.conduit.workflow.entity.WorkflowVersion
                                        .builder()
                                        .id(versionId)
                                        .build()
                        )
                        .build();

        TaskNode taskNode =
                TaskNode.builder()
                        .id(taskId)
                        .name("fetchUser")
                        .build();

        when(
                executionContextService.getVariables(
                        executionId
                )
        ).thenReturn(Map.of());

        when(
                workflowExecutionRepository.findById(
                        executionId
                )
        ).thenReturn(Optional.of(execution));

        when(
                taskNodeRepository.findByWorkflowVersion_Id(
                        versionId
                )
        ).thenReturn(List.of(taskNode));

        when(
                taskOutputService.getOutput(
                        executionId,
                        taskId
                )
        ).thenReturn(
                Map.of(
                        "success", true,
                        "output",
                        "{\"userId\":42,\"email\":\"user@example.com\"}",
                        "variables", Map.of(),
                        "metadata", Map.of()
                )
        );

        Map<String, Object> context =
                expressionContextService.buildContext(
                        executionId
                );

        assertTrue(context.containsKey("fetchUser"));

        Map<?, ?> fetchUser =
                (Map<?, ?>) context.get("fetchUser");

        Map<?, ?> output =
                (Map<?, ?>) fetchUser.get("output");

        assertEquals(42, output.get("userId"));

        assertEquals(
                "user@example.com",
                output.get("email")
        );
    }

    @Test
    void shouldPreserveNonJsonOutput() {

        Long executionId = 1L;
        Long versionId = 10L;
        Long taskId = 100L;

        WorkflowExecution execution =
                WorkflowExecution.builder()
                        .id(executionId)
                        .workflowVersion(
                                com.aryan.conduit.workflow.entity.WorkflowVersion
                                        .builder()
                                        .id(versionId)
                                        .build()
                        )
                        .build();

        TaskNode taskNode =
                TaskNode.builder()
                        .id(taskId)
                        .name("logTask")
                        .build();

        when(
                executionContextService.getVariables(
                        executionId
                )
        ).thenReturn(Map.of());

        when(
                workflowExecutionRepository.findById(
                        executionId
                )
        ).thenReturn(Optional.of(execution));

        when(
                taskNodeRepository.findByWorkflowVersion_Id(
                        versionId
                )
        ).thenReturn(List.of(taskNode));

        when(
                taskOutputService.getOutput(
                        executionId,
                        taskId
                )
        ).thenReturn(
                Map.of(
                        "success", true,
                        "output", "Hello World",
                        "variables", Map.of(),
                        "metadata", Map.of()
                )
        );

        Map<String, Object> context =
                expressionContextService.buildContext(
                        executionId
                );

        Map<?, ?> task =
                (Map<?, ?>) context.get("logTask");

        assertEquals(
                "Hello World",
                task.get("output")
        );
    }
}