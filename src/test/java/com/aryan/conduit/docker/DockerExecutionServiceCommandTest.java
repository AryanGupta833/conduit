package com.aryan.conduit.docker;

import com.aryan.conduit.execution.workspace.ExecutionWorkspaceService;
import com.aryan.conduit.observability.ConduitMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DockerExecutionServiceCommandTest {
    @TempDir Path tempDir;

    @Test
    void commandMountsOnlyManagedWorkingDirectoryAndKeepsReadOnlyRoot() throws Exception {
        var service = new DockerExecutionService(new ConduitMetrics(new SimpleMeterRegistry()));
        var workspace = new ExecutionWorkspaceService(tempDir.resolve("managed").toString(), 1024)
                .forExecution("2736");
        Path working = workspace.working();
        List<String> command = service.buildDockerCommand("conduit-task-test", "alpine:latest",
                List.of("sh", "-c", "echo test"), workspace, false);

        int mountFlag = command.indexOf("--mount");
        assertTrue(mountFlag >= 0);
        assertEquals("type=bind,source=" + working.toRealPath() + ",target=/conduit/workspace", command.get(mountFlag + 1));
        assertEquals("--read-only", command.get(command.indexOf("--read-only")));
        assertTrue(command.contains("--workdir"));
        assertEquals("/conduit/workspace", command.get(command.indexOf("--workdir") + 1));
        assertFalse(command.get(mountFlag + 1).contains(tempDir.resolve("project").toString()));
    }

    @Test
    void canMakeOnlyTheManagedMountReadOnly() throws Exception {
        var service = new DockerExecutionService(new ConduitMetrics(new SimpleMeterRegistry()));
        var workspace = new ExecutionWorkspaceService(tempDir.resolve("managed").toString(), 1024)
                .forExecution("100");
        List<String> command = service.buildDockerCommand("conduit-task-test", "alpine:latest",
                List.of("true"), workspace, true);

        assertTrue(command.contains("--read-only"));
        String expectedMount = "type=bind,source=" + workspace.working().toRealPath()
                + ",target=/conduit/workspace,readonly";
        assertTrue(command.contains(expectedMount));
    }

    @Test
    void scriptExecutionStreamsSourceAndKeepsWorkspaceMountSecurityFlags() throws Exception {
        var service = new DockerExecutionService(new ConduitMetrics(new SimpleMeterRegistry()));
        var workspace = new ExecutionWorkspaceService(tempDir.resolve("managed-script").toString(), 1024)
                .forExecution("101");
        String fixedWrapper = "cat > /tmp/.conduit-script && exec sh /tmp/.conduit-script </dev/null";
        List<String> command = service.buildDockerCommand("conduit-task-script", "alpine:latest",
                List.of("sh", "-c", fixedWrapper), workspace, false, true);

        assertTrue(command.contains("-i"));
        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("type=bind,source=" + workspace.working().toRealPath()
                + ",target=/conduit/workspace"));
        assertTrue(command.contains(fixedWrapper));
        assertFalse(command.stream().anyMatch(value -> value.contains("Shared workspace works!")));
    }
}
