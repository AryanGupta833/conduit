package com.aryan.conduit.plugin;

import com.aryan.conduit.execution.workspace.ExecutionWorkspaceService;
import com.aryan.conduit.docker.ManagedWorkspaceMount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DockerRunWorkspaceMountTest {
    @TempDir Path tempDir;

    @Test
    void usesSharedManagedWorkingMountAndIgnoresHostPathConfig() throws Exception {
        var plugin = new DockerRunPlugin();
        var workspace = new ExecutionWorkspaceService(tempDir.resolve("managed").toString(), 1024)
                .forExecution("2736");
        List<String> arguments = new ArrayList<>();

        plugin.addManagedWorkspaceMount(arguments, workspace,
                Map.of("workspaceRoot", "C:\\Users\\Aryan\\Desktop"), null);

        assertEquals(List.of("--mount", "type=bind,source=" + workspace.working().toRealPath()
                + ",target=" + ManagedWorkspaceMount.CONTAINER_PATH,
                "--workdir", ManagedWorkspaceMount.CONTAINER_PATH), arguments);
    }
}
