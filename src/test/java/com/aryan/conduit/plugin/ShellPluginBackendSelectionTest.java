package com.aryan.conduit.plugin;

import com.aryan.conduit.execution.TaskExecutionBackend;
import com.aryan.conduit.execution.TaskExecutionResult;
import com.aryan.conduit.execution.workspace.ExecutionWorkspace;
import com.aryan.conduit.execution.workspace.ExecutionWorkspaceService;
import com.aryan.conduit.docker.ManagedWorkspaceMount;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ShellPluginBackendSelectionTest {
    @Test
    void defaultsToDocker() {
        TaskExecutionBackend docker = mock(TaskExecutionBackend.class);
        TaskExecutionBackend kubernetes = mock(TaskExecutionBackend.class);
        when(docker.executeScript(anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(new TaskExecutionResult("d", "ok", "", 0, false));
        ShellPlugin plugin = new ShellPlugin(docker, kubernetes);
        PluginResult result = plugin.execute(context("{\"command\":\"echo hello\"}"));
        assertTrue(result.isSuccess());
        verify(docker).executeScript(eq("alpine:latest"), eq("echo hello"), eq(Duration.ofSeconds(30)),
                isNull(), eq(false));
        verifyNoInteractions(kubernetes);
    }

    @Test
    void selectsKubernetesAndCustomImage() {
        TaskExecutionBackend docker = mock(TaskExecutionBackend.class);
        TaskExecutionBackend kubernetes = mock(TaskExecutionBackend.class);
        when(kubernetes.execute(anyString(), anyList(), any())).thenReturn(new TaskExecutionResult("job", "ok", "", 0, false));
        ShellPlugin plugin = new ShellPlugin(docker, kubernetes);
        PluginResult result = plugin.execute(context("{\"command\":\"echo hello\",\"executor\":\"KUBERNETES\",\"image\":\"busybox:latest\"}"));
        assertTrue(result.isSuccess());
        verify(kubernetes).execute(eq("busybox:latest"), eq(List.of("sh", "-c", "echo hello")), eq(Duration.ofSeconds(30)));
        verifyNoInteractions(docker);
    }

    @Test
    void passesOnlyTheManagedExecutionWorkspaceToDockerBackend() throws Exception {
        TaskExecutionBackend docker = mock(TaskExecutionBackend.class);
        TaskExecutionBackend kubernetes = mock(TaskExecutionBackend.class);
        when(docker.executeScript(anyString(), anyString(), any(), any(ExecutionWorkspace.class), anyBoolean()))
                .thenReturn(new TaskExecutionResult("d", "ok", "", 0, false));
        var workspace = new ExecutionWorkspaceService(tempDir.resolve("managed").toString(), 1024).forExecution("2736");
        var plugin = new ShellPlugin(docker, kubernetes);

        PluginResult result = plugin.execute(new PluginContext("test", 30,
                Map.of("command", "echo from workspace", "workspaceRoot", tempDir.toString()), Map.of(), workspace));

        assertTrue(result.isSuccess());
        verify(docker).executeScript(eq("alpine:latest"),
                eq("cd '" + ManagedWorkspaceMount.CONTAINER_PATH + "' && echo from workspace"),
                eq(Duration.ofSeconds(30)), same(workspace), eq(false));
        verifyNoInteractions(kubernetes);
    }

    @Test
    void ignoresHostPathConfigurationWhenManagedWorkspaceIsAbsent() {
        TaskExecutionBackend docker = mock(TaskExecutionBackend.class);
        TaskExecutionBackend kubernetes = mock(TaskExecutionBackend.class);
        when(docker.executeScript(anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(new TaskExecutionResult("d", "ok", "", 0, false));
        ShellPlugin plugin = new ShellPlugin(docker, kubernetes);
        PluginContext context = new PluginContext("test", 30,
                Map.of("command", "echo no mount", "workspaceRoot", "C:\\Users\\Aryan\\Desktop"), Map.of());

        assertTrue(plugin.execute(context).isSuccess());
        verify(docker).executeScript(eq("alpine:latest"), eq("echo no mount"), eq(Duration.ofSeconds(30)),
                isNull(), eq(false));
        verifyNoInteractions(kubernetes);
    }

    @TempDir Path tempDir;

    private PluginContext context(String configuration) {
        try {
            return new PluginContext("test", 30,
                    new com.fasterxml.jackson.databind.ObjectMapper().readValue(configuration, new com.fasterxml.jackson.core.type.TypeReference<>() { }),
                    Map.of());
        } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
}
