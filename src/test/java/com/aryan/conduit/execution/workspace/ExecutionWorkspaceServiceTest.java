package com.aryan.conduit.execution.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionWorkspaceServiceTest {
    @TempDir Path root;

    @Test void scopesWorkspacesByExecutionAndRejectsTraversal() throws Exception {
        ExecutionWorkspaceService service = new ExecutionWorkspaceService(root.toString(), 1024);
        var one = service.forExecution("101");
        var two = service.forExecution("102");
        assertNotEquals(one.root(), two.root());
        assertTrue(one.resolve("artifacts/report.json").startsWith(one.root()));
        assertThrows(IllegalArgumentException.class, () -> one.resolve("../outside"));
    }

    @Test void rejectsAtomicWriteThatExceedsExecutionQuota() throws Exception {
        var workspace = new ExecutionWorkspaceService(root.toString(), 4).forExecution("104");
        assertThrows(WorkspaceLimitException.class, () -> workspace.writeAtomic("artifact.bin",
                new java.io.ByteArrayInputStream(new byte[5]), true, true));
        assertFalse(Files.exists(workspace.root().resolve("artifact.bin")));
        assertEquals(0, workspace.usedBytes());
    }

    @Test void quotaAccountingAllowsAtomicReplaceButPreservesOldFileOnOversize() throws Exception {
        var workspace = new ExecutionWorkspaceService(root.toString(), 4).forExecution("105");
        workspace.writeAtomic("artifact.bin", new java.io.ByteArrayInputStream(new byte[]{1, 2, 3, 4}), true, true);

        assertThrows(WorkspaceLimitException.class, () -> workspace.writeAtomic("artifact.bin",
                new java.io.ByteArrayInputStream(new byte[]{5, 6, 7, 8, 9}), true, true));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(workspace.root().resolve("artifact.bin")));
        assertEquals(4, workspace.usedBytes());
    }

    @Test void createsIsolatedWorkingDirectoriesForExecutions() throws Exception {
        ExecutionWorkspaceService service = new ExecutionWorkspaceService(root.toString(), 1024);
        var execution2736 = service.forExecution("2736");
        var execution200 = service.forExecution("200");

        Path firstWorkingDirectory = execution2736.working();
        Path secondWorkingDirectory = execution200.working();
        Files.writeString(firstWorkingDirectory.resolve("result.txt"), "only execution 2736");

        assertEquals(root.resolve("2736").resolve("working"), firstWorkingDirectory);
        assertTrue(Files.isDirectory(secondWorkingDirectory));
        assertNotEquals(firstWorkingDirectory, secondWorkingDirectory);
        assertFalse(Files.exists(secondWorkingDirectory.resolve("result.txt")));
    }

    @Test void rejectsSymlinkEscapeWhenSupported() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        var workspace = new ExecutionWorkspaceService(root.resolve("workspaces").toString(), 1024)
                .forExecution("103");
        try {
            Files.createSymbolicLink(workspace.root().resolve("escape"), outside);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException e) {
            return;
        }
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("escape/secret"));
    }
}
