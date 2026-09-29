package com.aryan.conduit.plugin;

import com.aryan.conduit.execution.TaskExecutionBackend;
import com.aryan.conduit.execution.TaskExecutionResult;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ShellPluginBackendSelectionTest {
    @Test
    void defaultsToDocker() {
        TaskExecutionBackend docker = mock(TaskExecutionBackend.class);
        TaskExecutionBackend kubernetes = mock(TaskExecutionBackend.class);
        when(docker.execute(anyString(), anyList(), any())).thenReturn(new TaskExecutionResult("d", "ok", "", 0, false));
        ShellPlugin plugin = new ShellPlugin(docker, kubernetes);
        PluginResult result = plugin.execute(context("{\"command\":\"echo hello\"}"));
        assertTrue(result.isSuccess());
        verify(docker).execute(eq("alpine:latest"), eq(List.of("sh", "-c", "echo hello")), eq(Duration.ofSeconds(30)));
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

    private PluginContext context(String configuration) {
        try {
            return new PluginContext("test", 30,
                    new com.fasterxml.jackson.databind.ObjectMapper().readValue(configuration, new com.fasterxml.jackson.core.type.TypeReference<>() { }),
                    Map.of());
        } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
}
