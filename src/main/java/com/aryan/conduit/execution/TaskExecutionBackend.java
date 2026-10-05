package com.aryan.conduit.execution;

import java.time.Duration;
import java.util.List;
import java.nio.file.Path;
import com.aryan.conduit.execution.workspace.ExecutionWorkspace;

public interface TaskExecutionBackend {

    TaskExecutionResult execute(
            String image,
            List<String> command,
            Duration timeout
    );

    /** @deprecated Callers must pass a server-created ExecutionWorkspace instead of a host path. */
    @Deprecated
    default TaskExecutionResult execute(String image, List<String> command, Duration timeout,
                                       Path workspaceRoot, boolean readOnlyWorkspace) {
        if (workspaceRoot != null) throw new UnsupportedOperationException("Host paths cannot be used as managed workspace mounts");
        return execute(image, command, timeout);
    }

    default TaskExecutionResult execute(String image, List<String> command, Duration timeout,
                                        ExecutionWorkspace workspace, boolean readOnlyWorkspace) {
        if (workspace != null) throw new UnsupportedOperationException("Execution backend does not support managed workspace mounts");
        return execute(image, command, timeout);
    }

    /** Execute shell source without placing user text in a Windows process argument. */
    default TaskExecutionResult executeScript(String image, String script, Duration timeout,
                                              ExecutionWorkspace workspace, boolean readOnlyWorkspace) {
        List<String> command = List.of("sh", "-c", script);
        return workspace == null
                ? execute(image, command, timeout)
                : execute(image, command, timeout, workspace, readOnlyWorkspace);
    }
}
