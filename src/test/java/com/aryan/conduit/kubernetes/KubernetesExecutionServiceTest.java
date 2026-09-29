package com.aryan.conduit.kubernetes;

import com.aryan.conduit.execution.TaskExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesExecutionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void manifestContainsJobImageCommandAndLimits() {
        KubernetesExecutionService service = new KubernetesExecutionService((c, i, t) -> null, mapper, "default");
        var manifest = service.jobManifest("conduit-task-id", "alpine:latest", List.of("sh", "-c", "echo hi"));
        var root = mapper.valueToTree(manifest);
        assertEquals("Job", root.path("kind").asText());
        assertEquals("alpine:latest", root.at("/spec/template/spec/containers/0/image").asText());
        assertEquals("echo hi", root.at("/spec/template/spec/containers/0/command/2").asText());
        assertEquals("256Mi", root.at("/spec/template/spec/containers/0/resources/limits/memory").asText());
    }

    @Test
    void reportsSuccessCollectsLogsAndDeletesJob() {
        AtomicInteger gets = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        KubectlCommandRunner runner = (command, stdin, timeout) -> {
            if (command.contains("apply")) {
                assertTrue(stdin.contains("alpine:latest"));
                assertTrue(stdin.contains("echo hi"));
                return result(0, "created", "");
            }
            if (command.contains("get")) return result(0, "{\"status\":{\"succeeded\":1}}", "");
            if (command.contains("logs")) return result(0, "hello\n", "");
            if (command.contains("delete")) { deletes.incrementAndGet(); return result(0, "", ""); }
            return result(1, "", "unexpected");
        };
        TaskExecutionResult result = new KubernetesExecutionService(runner, mapper, "default")
                .execute("alpine:latest", List.of("sh", "-c", "echo hi"), Duration.ofSeconds(2));
        assertTrue(result.isSuccess());
        assertEquals("hello\n", result.stdout());
        assertFalse(result.timedOut());
        assertEquals(1, deletes.get());
    }

    @Test
    void reportsFailureFromJobStatus() {
        KubectlCommandRunner runner = (command, stdin, timeout) -> {
            if (command.contains("get")) return result(0, "{\"status\":{\"failed\":1}}", "");
            if (command.contains("logs")) return result(0, "bad\n", "");
            return result(0, "", "");
        };
        TaskExecutionResult result = new KubernetesExecutionService(runner, mapper, "default")
                .execute("alpine:latest", List.of("sh", "-c", "exit 1"), Duration.ofSeconds(2));
        assertFalse(result.isSuccess());
        assertEquals(1, result.exitCode());
    }

    @Test
    void reportsTimeoutAndDeletesPendingJob() {
        AtomicInteger deletes = new AtomicInteger();
        KubectlCommandRunner runner = (command, stdin, timeout) -> {
            if (command.contains("get")) return result(0, "{\"status\":{}}", "");
            if (command.contains("logs")) return result(0, "partial output", "");
            if (command.contains("delete")) deletes.incrementAndGet();
            return result(0, "", "");
        };
        TaskExecutionResult result = new KubernetesExecutionService(runner, mapper, "default")
                .execute("alpine:latest", List.of("sh", "-c", "sleep 99"), Duration.ofMillis(100));
        assertTrue(result.timedOut());
        assertFalse(result.isSuccess());
        assertEquals(1, deletes.get());
    }

    private KubectlCommandRunner.CommandResult result(int code, String stdout, String stderr) {
        return new KubectlCommandRunner.CommandResult(code, stdout, stderr, false);
    }
}
