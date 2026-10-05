package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.workspace.ExecutionWorkspace;
import com.aryan.conduit.execution.workspace.ExecutionWorkspaceService;
import com.aryan.conduit.docker.DockerExecutionService;
import com.aryan.conduit.observability.ConduitMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.observability.ConduitMetrics;
import com.aryan.conduit.plugin.PluginManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TaskRunnerServiceWorkspacePathTest {

    @TempDir Path temporaryDirectory;
    private TaskRunnerService runner;
    private ExecutionWorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new ExecutionWorkspaceService(temporaryDirectory.resolve("workspaces").toString(), 1_000_000);
        runner = new TaskRunnerService(
                mock(com.aryan.conduit.execution.repository.TaskExecutionRepository.class),
                mock(TaskExecutionService.class), mock(ExecutionLogService.class), mock(TaskOutputService.class),
                mock(PluginManager.class), mock(com.aryan.conduit.execution.retry.RetryPolicyFactory.class),
                mock(IdempotencyService.class), new ObjectMapper(), mock(ConfigurationResolver.class),
                new ConduitMetrics(new SimpleMeterRegistry()), mock(ExpressionContextService.class), workspaceService);
    }

    @Test
    void resolvesPlainAndQuotedWindowsAbsolutePathsInsideWorkspace() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("1");
        Path file = workspace.root().resolve("folder").resolve("README");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ok");
        String windowsPath = file.toString();
        assertTrue(windowsPath.matches("^[A-Za-z]:.*"), "This regression case requires Windows paths");

        assertEquals(file, runner.resolveWorkspaceValue(workspace, windowsPath, true));
        assertEquals(file, runner.resolveWorkspaceValue(workspace, "\"" + windowsPath + "\"", true));
    }

    @Test
    void resolvesPlainAndQuotedWorkspaceRelativePaths() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("2");
        Files.createDirectories(workspace.root().resolve("folder"));
        Files.writeString(workspace.root().resolve("folder/README"), "ok");

        assertEquals(workspace.root().resolve("folder/README"), runner.resolveWorkspaceValue(workspace, "folder/README", true));
        assertEquals(workspace.root().resolve("folder/README"), runner.resolveWorkspaceValue(workspace, "\"folder/README\"", true));
    }

    @Test
    void rejectsTraversalAndAbsolutePathsOutsideWorkspace() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("3");
        assertThrows(PluginConfigurationException.class, () -> runner.resolveWorkspaceValue(workspace, "../outside", false));
        assertThrows(PluginConfigurationException.class, () -> runner.resolveWorkspaceValue(workspace, temporaryDirectory.resolve("outside").toString(), false));
    }

    @Test
    void rejectsSymlinkEscapeWhenPlatformAllowsCreatingSymlinks() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("4");
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(outside);
        Path link = workspace.root().resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.abort("Symlinks are unavailable in this test environment: " + e.getMessage());
        }
        assertThrows(PluginConfigurationException.class, () -> runner.resolveWorkspaceValue(workspace, "escape/file", false));
    }

    @Test
    void rejectsEmptyNullAndMalformedQuotedPaths() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("5");
        assertThrows(PluginConfigurationException.class, () -> runner.resolveWorkspaceValue(workspace, "", false));
        assertThrows(PluginConfigurationException.class, () -> runner.resolveWorkspaceValue(workspace, null, false));
        assertThrows(PluginConfigurationException.class, () -> runner.resolveWorkspaceValue(workspace, "\"unmatched", false));
        assertThrows(PluginConfigurationException.class, () -> runner.resolveWorkspaceValue(workspace, "\"inner\"quote\"", false));
    }

    @Test
    void quotedWorkspaceRootRemainsConfinedToManagedExecutionWorkspace() {
        assertThrows(PluginConfigurationException.class, () -> runner.configureWorkspace("FILE_READ",
                Map.of("workspaceRoot", "\"" + temporaryDirectory.toAbsolutePath() + "\""), 6L));
    }

    @Test
    void logicalWorkspaceRootResolvesAgainstCurrentExecution() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("2736");

        Map<String, Object> configured = runner.configureWorkspace("FILE_READ",
                Map.of("workspaceRoot", "workspace:/working", "path", "shared-test/result.txt"), 2736L);

        assertEquals(workspace.root().toString(), configured.get("workspaceRoot"));
        assertEquals(Path.of("working", "shared-test", "result.txt").toString(), configured.get("path"));
        assertEquals(workspace.working(), runner.resolveWorkspaceValue(workspace, "workspace:/working", false));
    }

    @Test
    void logicalWorkspaceRootsUseExecutionWorkspaceResolverAndRejectTraversal() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("2737");
        assertEquals(workspace.root(), runner.resolveWorkspaceValue(workspace, "workspace:/", false));
        assertEquals(workspace.root().resolve("working"), runner.resolveWorkspaceValue(workspace, "workspace:/working", false));
        assertEquals(workspace.root().resolve("artifacts"), runner.resolveWorkspaceValue(workspace, "workspace:/artifacts", false));
        assertEquals(workspace.root().resolve("tasks/123"), runner.resolveWorkspaceValue(workspace, "workspace:/tasks/123", false));
        assertThrows(PluginConfigurationException.class, () -> runner.configureWorkspace("FILE_READ",
                Map.of("workspaceRoot", "workspace:/../outside"), 2737L));
        assertThrows(PluginConfigurationException.class, () -> runner.configureWorkspace("FILE_READ",
                Map.of("workspaceRoot", "workspace:/../../outside"), 2737L));
    }

    @Test
    void rejectsWindowsDriveAndUncWorkspaceRootsOutsideManagedWorkspace() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        assertThrows(PluginConfigurationException.class, () -> runner.configureWorkspace("FILE_READ",
                Map.of("workspaceRoot", "C:\\Users\\outside"), 2739L));
        assertThrows(PluginConfigurationException.class, () -> runner.configureWorkspace("FILE_READ",
                Map.of("workspaceRoot", "\\\\server\\share"), 2740L));
    }

    @Test
    void logicalWorkspaceRootAllowsFileReadOfPreviouslyCreatedWorkingFile() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("2738");
        Path sharedFile = workspace.resolve("working/shared-test/result.txt");
        Files.createDirectories(sharedFile.getParent());
        Files.writeString(sharedFile, "Shared workspace works!");

        Map<String, Object> configured = runner.configureWorkspace("FILE_READ",
                Map.of("encoding", "UTF-8", "path", "shared-test/result.txt", "workspaceRoot", "workspace:/working"), 2738L);
        com.aryan.conduit.plugin.sdk.PluginResult result = new com.aryan.conduit.plugin.FileReadPlugin().execute(
                new com.aryan.conduit.plugin.sdk.PluginContext("read shared output", 30, configured, Map.of(), workspace));

        assertTrue(result.isSuccess(), result.getOutput());
        assertEquals("Shared workspace works!", result.getOutput());
    }

    @Test
    void dockerShellOutputCanBeReadFromTheManagedWorkingWorkspaceWhenOptedIn() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("conduit.test.dockerIntegration"),
                "Set conduit.test.dockerIntegration=true to run the Docker integration test");
        ExecutionWorkspace workspace = workspaceService.forExecution("2783");
        Path working = workspace.working();
        String command = "mkdir -p /conduit/workspace/shared-test && "
                + "echo \"Shared workspace works!\" > /conduit/workspace/shared-test/result.txt";
        var docker = new DockerExecutionService(new ConduitMetrics(new SimpleMeterRegistry()));
        var shellPlugin = new com.aryan.conduit.plugin.ShellPlugin(docker, mock(com.aryan.conduit.execution.TaskExecutionBackend.class));
        var shellResult = shellPlugin.execute(new com.aryan.conduit.plugin.sdk.PluginContext("write shared file", 90,
                Map.of("executor", "DOCKER", "image", "alpine:latest", "command", command), Map.of(), workspace));
        assertTrue(shellResult.isSuccess(), shellResult.getOutput());
        Path createdFile = working.resolve("shared-test/result.txt");
        assertTrue(java.nio.file.Files.isRegularFile(createdFile),
                "SHELL command did not create " + createdFile + "; Docker output: "
                        + shellResult.getOutput());

        Map<String, Object> configured = runner.configureWorkspace("FILE_READ",
                Map.of("encoding", "UTF-8", "path", "shared-test/result.txt", "workspaceRoot", "workspace:/working"),
                2783L);
        com.aryan.conduit.plugin.sdk.PluginResult result = new com.aryan.conduit.plugin.FileReadPlugin().execute(
                new com.aryan.conduit.plugin.sdk.PluginContext("read Docker output", 30, configured, Map.of(), workspace));

        assertTrue(result.isSuccess(), result.getOutput());
        assertEquals("Shared workspace works!", result.getOutput().stripTrailing());
    }

    @Test
    void emptyS3UploadFilePathIsPreservedForContentMode() {
        Map<String, Object> configured = runner.configureWorkspace("S3_UPLOAD",
                Map.of("filePath", "", "content", "inline payload"), 7L);
        assertEquals("", configured.get("filePath"));
        assertEquals("inline payload", configured.get("content"));
        assertTrue(configured.containsKey("workspaceRoot"));
    }

    @Test
    void s3UploadRelativeFilePathIsResolvedInsideConfiguredLogicalWorkspaceRoot() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("2750");
        Path workingDirectory = workspace.working();
        Path zipFile = workingDirectory.resolve("conduit.zip");
        Files.write(zipFile, new byte[] {1, 2, 3});

        Map<String, Object> configured = runner.configureWorkspace("S3_UPLOAD",
                Map.of("bucket", "test", "key", "conduit-test/conduit.zip",
                        "filePath", "conduit.zip", "workspaceRoot", "workspace:/working"), 2750L);

        assertEquals(workspace.root().toString(), configured.get("workspaceRoot"));
        Path resolvedFile = Path.of(String.valueOf(configured.get("filePath")));
        assertEquals(zipFile, resolvedFile);
        assertTrue(Files.isDirectory(workingDirectory));
        assertTrue(Files.isRegularFile(resolvedFile));
    }

    @Test
    void s3UploadRejectsMissingFileWithinConfiguredLogicalWorkspaceRoot() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("2751");
        workspace.working();

        assertThrows(PluginConfigurationException.class, () -> runner.configureWorkspace("S3_UPLOAD",
                Map.of("filePath", "conduit.zip", "workspaceRoot", "workspace:/working"), 2751L));
    }

    @Test
    void s3DownloadDestinationIsScopedToConfiguredLogicalWorkspaceRoot() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("2752");

        Map<String, Object> configured = runner.configureWorkspace("S3_DOWNLOAD",
                Map.of("bucket", "test", "key", "roundtrip/test.txt",
                        "destinationPath", "aws-roundtrip/downloaded.txt",
                        "workspaceRoot", "workspace:/working"), 2752L);

        assertEquals(workspace.root().toString(), configured.get("workspaceRoot"));
        assertEquals(Path.of("working", "aws-roundtrip", "downloaded.txt"),
                Path.of(String.valueOf(configured.get("destinationPath"))));
    }

    @Test
    void fileWriteAndGitPathsShareWorkspaceRootScoping() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("2753");
        Map<String, Object> fileWrite = runner.configureWorkspace("FILE_WRITE",
                Map.of("path", "notes/result.txt", "content", "ok", "workspaceRoot", "workspace:/working"), 2753L);
        Map<String, Object> gitClone = runner.configureWorkspace("GIT",
                Map.of("operation", "CLONE", "repository", "https://example.invalid/repo.git",
                        "destinationPath", "repo", "workspaceRoot", "workspace:/working"), 2753L);

        assertEquals(Path.of("working", "notes", "result.txt"), Path.of(String.valueOf(fileWrite.get("path"))));
        assertEquals(Path.of("working", "repo"), Path.of(String.valueOf(gitClone.get("destinationPath"))));
        assertEquals(workspace.root().toString(), fileWrite.get("workspaceRoot"));
        assertEquals(workspace.root().toString(), gitClone.get("workspaceRoot"));
    }

    @Test
    void configuredWorkspaceRootMustBeADirectory() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("2754");
        Path file = workspace.root().resolve("not-a-directory");
        Files.writeString(file, "file");

        assertThrows(PluginConfigurationException.class, () -> runner.configureWorkspace("FILE_WRITE",
                Map.of("path", "result.txt", "content", "value", "workspaceRoot", "workspace:/not-a-directory"), 2754L));
    }

    @Test
    void shellReceivesContainerPathButNoWorkflowControlledHostMountPath() throws Exception {
        ExecutionWorkspace workspace = workspaceService.forExecution("8");
        Map<String, Object> configuration = new java.util.HashMap<>();
        configuration.put("workingDirectory", "workspace:/shell-test");
        configuration.put("workspaceRoot", workspace.root().resolve("working").toString());

        Map<String, Object> configured = runner.configureWorkspace("SHELL", configuration, 8L);

        assertEquals("/conduit/workspace/shell-test", configured.get("workingDirectory"));
        assertFalse(configured.containsKey("workspaceRoot"));
        assertThrows(PluginConfigurationException.class, () -> runner.configureWorkspace("SHELL",
                Map.of("workspaceRoot", temporaryDirectory.toString()), 9L));
    }
}
