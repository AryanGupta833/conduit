package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.retry.RetryPolicyFactory;
import com.aryan.conduit.execution.workspace.ExecutionWorkspace;
import com.aryan.conduit.execution.workspace.ExecutionWorkspaceService;
import com.aryan.conduit.observability.ConduitMetrics;
import com.aryan.conduit.plugin.PluginManager;
import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import com.aryan.conduit.workflow.entity.TaskNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskRunnerRetryClassificationTest {

    @Test
    void configurationFailureIsNotRetriedEvenWhenRetriesAreConfigured() throws Exception {
        TaskExecutionRepository tasks = mock(TaskExecutionRepository.class);
        TaskExecutionService states = mock(TaskExecutionService.class);
        ExecutionLogService logs = mock(ExecutionLogService.class);
        TaskOutputService outputs = mock(TaskOutputService.class);
        PluginManager plugins = mock(PluginManager.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        ConfigurationResolver resolver = mock(ConfigurationResolver.class);
        ExpressionContextService expressionContext = mock(ExpressionContextService.class);
        ExecutionWorkspaceService workspaceService = mock(ExecutionWorkspaceService.class);
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        WorkflowPlugin plugin = mock(WorkflowPlugin.class);

        TaskNode node = TaskNode.builder().id(10L).name("fetch")
                .pluginType("HTTP").configurationJson("{}")
                .maxRetries(5).timeoutSeconds(30).build();
        TaskExecution task = TaskExecution.builder().id(5L).idempotencyKey("100:10")
                .taskNode(node).workflowExecution(WorkflowExecution.builder().id(100L).build()).build();
        when(tasks.findById(5L)).thenReturn(Optional.of(task));
        when(plugins.getPlugin("HTTP")).thenReturn(plugin);
        when(resolver.resolve(100L, "{}")).thenReturn("{}");
        when(expressionContext.buildContext(100L)).thenReturn(Map.of());
        when(workspaceService.forExecution("100")).thenReturn(workspace);
        when(workspace.root()).thenReturn(Path.of("target/test-workspace"));
        when(workspace.usedBytes()).thenReturn(0L);
        when(workspace.maximumBytes()).thenReturn(1024L);
        when(idempotency.getCompletedResult("100:10")).thenReturn(Optional.empty());
        when(idempotency.tryStart("100:10")).thenReturn(Optional.of(UUID.randomUUID()));
        when(states.tryMarkRunning(5L)).thenReturn(TaskExecutionService.StartDecision.STARTED);
        doThrow(new PluginConfigurationException("invalid URL"))
                .when(plugin).validateConfiguration(anyMap());

        TaskRunnerService runner = new TaskRunnerService(tasks, states, logs, outputs, plugins,
                new RetryPolicyFactory(), idempotency, new ObjectMapper(), resolver,
                new ConduitMetrics(new SimpleMeterRegistry()), expressionContext, workspaceService);

        assertThrows(IllegalArgumentException.class, () -> runner.executeWithRetry(5L, 5, 30));

        verify(plugin, times(1)).validateConfiguration(anyMap());
        verify(states, never()).markRetrying(5L);
        verify(states).markFailed(5L);
    }
}
