package com.aryan.conduit.docker;

import com.aryan.conduit.execution.workspace.ExecutionWorkspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Creates Docker bind-mount arguments only from a server-owned execution workspace. */
public final class ManagedWorkspaceMount {
    public static final String CONTAINER_PATH = "/conduit/workspace";

    private ManagedWorkspaceMount() {}

    public static String dockerMount(ExecutionWorkspace workspace, boolean readOnly) throws IOException {
        if (workspace == null) throw new IllegalArgumentException("Managed execution workspace is required");

        Path executionRoot = workspace.root().toRealPath();
        Path configuredWorkingDirectory = workspace.working();
        Path workingDirectory = configuredWorkingDirectory.toRealPath();
        if (!workingDirectory.isAbsolute() || !workingDirectory.normalize().equals(workingDirectory))
            throw new IllegalArgumentException("Managed workspace mount path must be absolute and normalized");
        if (!workingDirectory.getParent().equals(executionRoot)
                || !workingDirectory.getFileName().toString().equals("working")
                || !Files.isDirectory(workingDirectory, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Managed workspace mount must be the execution working directory");

        return "type=bind,source=" + workingDirectory + ",target=" + CONTAINER_PATH
                + (readOnly ? ",readonly" : "");
    }
}
