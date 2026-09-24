package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.retry.RetryPolicyFactory;
import com.aryan.conduit.plugin.PluginManager;
import com.aryan.conduit.plugin.PluginResult;
import com.aryan.conduit.plugin.WorkflowPlugin;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRunnerServiceConfigurationTest {

    private TaskExecutionRepository taskExecutionRepository;
    private WorkflowExecutionRepository workflowExecutionRepository;
    private TaskExecutionService taskExecutionService;
    private ExecutionLogService executionLogService;
    private TaskOutputService taskOutputService;
    private CircuitBreakerTaskService circuitBreakerTaskService;
    private PluginManager pluginManager;
    private RetryPolicyFactory retryPolicyFactory;
    private IdempotencyService idempotencyService;
    private ObjectMapper objectMapper;
    private ConfigurationResolver configurationResolver;

    private TaskRunnerService taskRunnerService;

    private WorkflowPlugin plugin;

    @BeforeEach
    void setUp() {

        taskExecutionRepository =
                mock(TaskExecutionRepository.class);

        workflowExecutionRepository =
                mock(WorkflowExecutionRepository.class);

        taskExecutionService =
                mock(TaskExecutionService.class);

        executionLogService =
                mock(ExecutionLogService.class);

        taskOutputService =
                mock(TaskOutputService.class);

        circuitBreakerTaskService =
                mock(CircuitBreakerTaskService.class);

        pluginManager =
                mock(PluginManager.class);

        retryPolicyFactory =
                mock(RetryPolicyFactory.class);

        idempotencyService =
                mock(IdempotencyService.class);

        objectMapper =
                new ObjectMapper();

        configurationResolver =
                mock(ConfigurationResolver.class);

        plugin =
                mock(WorkflowPlugin.class);

        taskRunnerService =
                new TaskRunnerService(
                        taskExecutionRepository,
                        workflowExecutionRepository,
                        taskExecutionService,
                        executionLogService,
                        taskOutputService,
                        circuitBreakerTaskService,
                        pluginManager,
                        retryPolicyFactory,
                        idempotencyService,
                        objectMapper,
                        configurationResolver
                );
    }

    @Test
    void shouldPassResolvedConfigurationToPlugin()
            throws Exception {

        Long taskExecutionId = 1L;
        Long workflowExecutionId = 100L;

        String originalConfiguration =
                """
                {
                    "url": "https://example.com/users/${userId}"
                }
                """;

        String resolvedConfiguration =
                """
                {
                    "url": "https://example.com/users/42"
                }
                """;

        WorkflowExecution workflowExecution =
                mock(WorkflowExecution.class);

        when(workflowExecution.getId())
                .thenReturn(workflowExecutionId);

        TaskNode taskNode =
                TaskNode.builder()
                        .id(10L)
                        .name("fetchUser")
                        .pluginType("HTTP")
                        .configurationJson(originalConfiguration)
                        .timeoutSeconds(30)
                        .maxRetries(1)
                        .build();

        TaskExecution taskExecution =
                mock(TaskExecution.class);

        when(taskExecution.getId())
                .thenReturn(taskExecutionId);

        when(taskExecution.getIdempotencyKey())
                .thenReturn("test-idempotency-key");

        when(taskExecution.getWorkflowExecution())
                .thenReturn(workflowExecution);

        when(taskExecution.getTaskNode())
                .thenReturn(taskNode);

        when(taskExecutionRepository.findById(taskExecutionId))
                .thenReturn(Optional.of(taskExecution));

        when(pluginManager.getPlugin("HTTP"))
                .thenReturn(plugin);

        when(configurationResolver.resolve(
                workflowExecutionId,
                originalConfiguration
        )).thenReturn(resolvedConfiguration);

        when(idempotencyService.getCompletedResult(
                "test-idempotency-key"
        )).thenReturn(Optional.empty());

        UUID leaseToken =
                UUID.randomUUID();

        when(idempotencyService.tryStart(
                "test-idempotency-key"
        )).thenReturn(Optional.of(leaseToken));

        /*
         * The production code requires successful
         * idempotency completion before the task can finish.
         */
        when(idempotencyService.complete(
                anyString(),
                any(UUID.class),
                anyString()
        )).thenReturn(true);

        PluginResult pluginResult =
                PluginResult.builder()
                        .success(true)
                        .output("OK")
                        .build();

        when(plugin.execute(
                any(TaskNode.class),
                anyMap()
        )).thenReturn(pluginResult);

        taskRunnerService.executeWithRetry(
                taskExecutionId,
                0,
                30
        );

        verify(configurationResolver)
                .resolve(
                        workflowExecutionId,
                        originalConfiguration
                );

        verify(plugin)
                .execute(
                        argThat(runtimeTaskNode ->
                                runtimeTaskNode
                                        .getConfigurationJson()
                                        .contains(
                                                "https://example.com/users/42"
                                        )
                        ),
                        anyMap()
                );
    }
}