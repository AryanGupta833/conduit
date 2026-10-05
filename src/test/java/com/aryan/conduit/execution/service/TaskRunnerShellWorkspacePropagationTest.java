package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.retry.RetryPolicyFactory;
import com.aryan.conduit.execution.workspace.ExecutionWorkspace;
import com.aryan.conduit.execution.workspace.ExecutionWorkspaceService;
import com.aryan.conduit.observability.ConduitMetrics;
import com.aryan.conduit.plugin.PluginManager;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskRunnerShellWorkspacePropagationTest {
    @TempDir Path tempDir;

    @Test
    void passesExecutionScopedWorkspaceFromServiceIntoPluginContext() throws Exception {
        TaskExecutionRepository taskRepository = mock(TaskExecutionRepository.class);
        TaskExecutionService taskService = mock(TaskExecutionService.class);
        ExecutionLogService logService = mock(ExecutionLogService.class);
        TaskOutputService outputService = mock(TaskOutputService.class);
        PluginManager pluginManager = mock(PluginManager.class);
        RetryPolicyFactory retryPolicyFactory = mock(RetryPolicyFactory.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        ConfigurationResolver configResolver = mock(ConfigurationResolver.class);
        ExpressionContextService expressionContext = mock(ExpressionContextService.class);
        var workspaceService = new ExecutionWorkspaceService(tempDir.resolve("managed").toString(), 1024 * 1024);
        var runner = new TaskRunnerService(taskRepository, taskService, logService, outputService, pluginManager,
                retryPolicyFactory, idempotencyService, new ObjectMapper(), configResolver,
                new ConduitMetrics(new SimpleMeterRegistry()), expressionContext, workspaceService);

        long taskId = 7L;
        long executionId = 2736L;
        String config = "{\"command\":\"echo workspace\",\"executor\":\"DOCKER\"}";
        var node = TaskNode.builder().id(70L).name("shell").pluginType("SHELL")
                .configurationJson(config).timeoutSeconds(30).maxRetries(0).build();
        var workflowExecution = mock(WorkflowExecution.class);
        when(workflowExecution.getId()).thenReturn(executionId);
        var task = mock(TaskExecution.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getIdempotencyKey()).thenReturn("shell-workspace-test");
        when(task.getWorkflowExecution()).thenReturn(workflowExecution);
        when(task.getTaskNode()).thenReturn(node);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(configResolver.resolve(executionId, config)).thenReturn(config);
        when(expressionContext.buildContext(executionId)).thenReturn(Map.of());
        when(idempotencyService.getCompletedResult("shell-workspace-test")).thenReturn(Optional.empty());
        UUID leaseToken = UUID.randomUUID();
        when(idempotencyService.tryStart("shell-workspace-test")).thenReturn(Optional.of(leaseToken));
        when(idempotencyService.complete(anyString(), any(UUID.class), anyString())).thenReturn(true);

        WorkflowPlugin plugin = mock(WorkflowPlugin.class);
        when(plugin.execute(any())).thenAnswer(invocation -> {
            var context = (com.aryan.conduit.plugin.sdk.PluginContext) invocation.getArgument(0);
            assertInstanceOf(ExecutionWorkspace.class, context.workspace());
            var workspace = (ExecutionWorkspace) context.workspace();
            assertEquals(tempDir.resolve("managed").resolve("2736"), workspace.root());
            assertTrue(java.nio.file.Files.isDirectory(workspace.working()));
            assertFalse(context.configuration().containsKey("workspaceRoot"));
            return PluginResult.builder().success(true).output("ok").build();
        });
        when(pluginManager.getPlugin("SHELL")).thenReturn(plugin);

        Map<String, Object> result = runner.executeWithRetry(taskId, 0, 30);

        assertEquals(true, result.get("success"));
        verify(plugin).execute(any());
    }
}
