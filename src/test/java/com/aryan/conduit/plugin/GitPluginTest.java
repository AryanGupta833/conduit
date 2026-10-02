package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitPluginTest {

    private GitPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GitPlugin();
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    void exposesCorrectMetadata() {

        var metadata = plugin.metadata();

        assertEquals(
                "GIT",
                metadata.type()
        );

        assertEquals(
                "Git",
                metadata.displayName()
        );

        assertEquals(
                "1.0.0",
                metadata.version()
        );
    }

    // -------------------------------------------------------------------------
    // STATUS
    // -------------------------------------------------------------------------

    @Test
    void statusReturnsRepositoryInformation(
            @TempDir Path tempDir)
            throws Exception {

        Path repository =
                createRepository(tempDir);

        PluginContext context = context(
                Map.of(
                        "operation", "STATUS",
                        "workspaceRoot", tempDir.toString(),
                        "repositoryPath", "repo"
                )
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertEquals(
                "STATUS",
                result.getMetadata().get("operation")
        );

        assertEquals(
                true,
                result.getMetadata().get("success")
        );

        assertEquals(
                0,
                result.getMetadata().get("exitCode")
        );

        assertEquals(
                false,
                result.getMetadata().get("timedOut")
        );

        assertNotNull(
                result.getMetadata().get("durationMs")
        );

        // `git status --short --branch` normally returns
        // output such as "## master" or "## main".
        assertTrue(
                result.getOutput().contains("##"),
                "Expected Git status output, but got: "
                        + result.getOutput()
        );

        assertTrue(
                Files.exists(
                        repository.resolve(".git")
                )
        );
    }

    // -------------------------------------------------------------------------
    // BRANCH
    // -------------------------------------------------------------------------

    @Test
    void branchListsRepositoryBranches(
            @TempDir Path tempDir)
            throws Exception {

        Path repository =
                createRepository(tempDir);

        runGit(
                repository,
                "branch",
                "feature/test"
        );

        PluginContext context = context(
                Map.of(
                        "operation", "BRANCH",
                        "workspaceRoot", tempDir.toString(),
                        "repositoryPath", "repo"
                )
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertEquals(
                "BRANCH",
                result.getMetadata().get("operation")
        );

        assertTrue(
                result.getOutput().contains(
                        "feature/test"
                )
        );
    }

    // -------------------------------------------------------------------------
    // LOG
    // -------------------------------------------------------------------------

    @Test
    void logReturnsCommitHistory(
            @TempDir Path tempDir)
            throws Exception {

        Path repository =
                createRepository(tempDir);

        Files.writeString(
                repository.resolve("file.txt"),
                "hello"
        );

        runGit(
                repository,
                "add",
                "."
        );

        runGit(
                repository,
                "commit",
                "-m",
                "second commit"
        );

        PluginContext context = context(
                Map.of(
                        "operation", "LOG",
                        "workspaceRoot", tempDir.toString(),
                        "repositoryPath", "repo",
                        "count", 5
                )
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertEquals(
                "LOG",
                result.getMetadata().get("operation")
        );

        assertEquals(
                5,
                result.getMetadata().get("count")
        );

        assertTrue(
                result.getOutput().contains(
                        "second commit"
                )
        );

        assertTrue(
                result.getOutput().contains(
                        "initial commit"
                )
        );
    }

    // -------------------------------------------------------------------------
    // CHECKOUT
    // -------------------------------------------------------------------------

    @Test
    void checkoutSwitchesBranch(
            @TempDir Path tempDir)
            throws Exception {

        Path repository =
                createRepository(tempDir);

        runGit(
                repository,
                "checkout",
                "-b",
                "feature"
        );

        runGit(
                repository,
                "checkout",
                "-"
        );

        PluginContext context = context(
                Map.of(
                        "operation", "CHECKOUT",
                        "workspaceRoot", tempDir.toString(),
                        "repositoryPath", "repo",
                        "ref", "feature"
                )
        );

        var result = plugin.execute(context);

        assertTrue(
                result.isSuccess(),
                "Checkout failed: " + result.getOutput()
        );

        assertEquals(
                "CHECKOUT",
                result.getMetadata().get("operation")
        );

        assertEquals(
                "feature",
                runGit(
                        repository,
                        "branch",
                        "--show-current"
                ).trim()
        );
    }

    // -------------------------------------------------------------------------
    // FETCH
    // -------------------------------------------------------------------------

    @Test
    void fetchWorksOnRepository(
            @TempDir Path tempDir)
            throws Exception {

        Path repository =
                createRepository(tempDir);

        PluginContext context = context(
                Map.of(
                        "operation", "FETCH",
                        "workspaceRoot", tempDir.toString(),
                        "repositoryPath", "repo"
                )
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertEquals(
                "FETCH",
                result.getMetadata().get("operation")
        );

        assertEquals(
                0,
                result.getMetadata().get("exitCode")
        );
    }

    // -------------------------------------------------------------------------
    // PULL
    // -------------------------------------------------------------------------

    @Test
    void pullFailsWhenRepositoryHasNoUpstream(
            @TempDir Path tempDir)
            throws Exception {

        createRepository(tempDir);

        PluginContext context = context(
                Map.of(
                        "operation", "PULL",
                        "workspaceRoot", tempDir.toString(),
                        "repositoryPath", "repo"
                )
        );

        var result = plugin.execute(context);

        assertFalse(result.isSuccess());

        assertEquals(
                "PULL",
                result.getMetadata().get("operation")
        );

        assertNotEquals(
                0,
                result.getMetadata().get("exitCode")
        );

        assertFalse(
                (Boolean) result.getMetadata()
                        .get("timedOut")
        );
    }

    // -------------------------------------------------------------------------
    // CLONE
    // -------------------------------------------------------------------------

    @Test
    void cloneCopiesLocalRepository(
            @TempDir Path tempDir)
            throws Exception {

        Path source =
                tempDir.resolve("source");

        Path workspace =
                tempDir.resolve("workspace");

        Files.createDirectories(source);
        Files.createDirectories(workspace);

        runGit(
                source,
                "init"
        );

        runGit(
                source,
                "config",
                "user.email",
                "test@example.com"
        );

        runGit(
                source,
                "config",
                "user.name",
                "Conduit Test"
        );

        Files.writeString(
                source.resolve("hello.txt"),
                "hello conduit"
        );

        runGit(
                source,
                "add",
                "."
        );

        runGit(
                source,
                "commit",
                "-m",
                "initial"
        );

        Path destination =
                workspace.resolve("cloned");

        PluginContext context = context(
                Map.of(
                        "operation", "CLONE",
                        "repository",
                        source.toString(),
                        "workspaceRoot",
                        workspace.toString(),
                        "destinationPath",
                        "cloned",
                        "timeoutSeconds",
                        30
                )
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertTrue(
                Files.exists(
                        destination.resolve(".git")
                )
        );

        assertEquals(
                "hello conduit",
                Files.readString(
                        destination.resolve("hello.txt")
                )
        );

        assertEquals(
                "CLONE",
                result.getMetadata().get("operation")
        );

        assertEquals(
                source.toString(),
                result.getMetadata().get("repository")
        );

        assertEquals(
                destination.toString(),
                result.getMetadata().get("destinationPath")
        );
    }

    // -------------------------------------------------------------------------
    // Clone with branch
    // -------------------------------------------------------------------------

    @Test
    void cloneCanCheckoutSpecifiedBranch(
            @TempDir Path tempDir)
            throws Exception {

        Path source =
                tempDir.resolve("source");

        Path workspace =
                tempDir.resolve("workspace");

        Files.createDirectories(source);
        Files.createDirectories(workspace);

        runGit(
                source,
                "init"
        );

        runGit(
                source,
                "config",
                "user.email",
                "test@example.com"
        );

        runGit(
                source,
                "config",
                "user.name",
                "Conduit Test"
        );

        Files.writeString(
                source.resolve("main.txt"),
                "main"
        );

        runGit(
                source,
                "add",
                "."
        );

        runGit(
                source,
                "commit",
                "-m",
                "main commit"
        );

        runGit(
                source,
                "checkout",
                "-b",
                "feature"
        );

        Files.writeString(
                source.resolve("feature.txt"),
                "feature"
        );

        runGit(
                source,
                "add",
                "."
        );

        runGit(
                source,
                "commit",
                "-m",
                "feature commit"
        );

        PluginContext context = context(
                Map.of(
                        "operation", "CLONE",
                        "repository", source.toString(),
                        "workspaceRoot", workspace.toString(),
                        "destinationPath", "cloned",
                        "branch", "feature"
                )
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        Path cloned =
                workspace.resolve("cloned");

        assertTrue(
                Files.exists(
                        cloned.resolve("feature.txt")
                )
        );

        assertEquals(
                "feature",
                runGit(
                        cloned,
                        "branch",
                        "--show-current"
                ).trim()
        );
    }

    // -------------------------------------------------------------------------
    // Configuration validation
    // -------------------------------------------------------------------------

    @Test
    void rejectsNullConfiguration() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(null)
        );
    }

    @Test
    void rejectsMissingOperation() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of()
                )
        );
    }

    @Test
    void rejectsUnsupportedOperation() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "PUSH"
                        )
                )
        );
    }

    @Test
    void rejectsCloneWithoutRepository(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "CLONE",
                                "workspaceRoot",
                                tempDir.toString(),
                                "destinationPath",
                                "repo"
                        )
                )
        );
    }

    @Test
    void rejectsCloneWithoutDestination(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "CLONE",
                                "repository",
                                "https://github.com/example/test.git",
                                "workspaceRoot",
                                tempDir.toString()
                        )
                )
        );
    }

    @Test
    void rejectsRepositoryPathTraversal(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "STATUS",
                                "workspaceRoot",
                                tempDir.toString(),
                                "repositoryPath",
                                "../repo"
                        )
                )
        );
    }

    @Test
    void rejectsDestinationPathTraversal(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "CLONE",
                                "repository",
                                "https://github.com/example/test.git",
                                "workspaceRoot",
                                tempDir.toString(),
                                "destinationPath",
                                "../repo"
                        )
                )
        );
    }

    @Test
    void rejectsAbsoluteDestinationPath(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "CLONE",
                                "repository",
                                "https://github.com/example/test.git",
                                "workspaceRoot",
                                tempDir.toString(),
                                "destinationPath",
                                tempDir
                                        .resolve("repo")
                                        .toString()
                        )
                )
        );
    }

    @Test
    void rejectsEmbeddedCredentialsInRepository() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "CLONE",
                                "repository",
                                "https://user:password@example.com/repo.git",
                                "workspaceRoot",
                                "/workspace",
                                "destinationPath",
                                "repo"
                        )
                )
        );
    }

    @Test
    void rejectsExtTransport() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "CLONE",
                                "repository",
                                "ext::sh -c malicious",
                                "workspaceRoot",
                                "/workspace",
                                "destinationPath",
                                "repo"
                        )
                )
        );
    }

    @Test
    void rejectsInvalidRefStartingWithDash(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "CHECKOUT",
                                "workspaceRoot",
                                tempDir.toString(),
                                "repositoryPath",
                                "repo",
                                "ref",
                                "--malicious"
                        )
                )
        );
    }

    @Test
    void rejectsRefContainingNewline(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "CHECKOUT",
                                "workspaceRoot",
                                tempDir.toString(),
                                "repositoryPath",
                                "repo",
                                "ref",
                                "main\nmalicious"
                        )
                )
        );
    }

    @Test
    void rejectsInvalidTimeout(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "STATUS",
                                "workspaceRoot",
                                tempDir.toString(),
                                "repositoryPath",
                                "repo",
                                "timeoutSeconds",
                                0
                        )
                )
        );
    }

    @Test
    void rejectsExcessiveTimeout(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "STATUS",
                                "workspaceRoot",
                                tempDir.toString(),
                                "repositoryPath",
                                "repo",
                                "timeoutSeconds",
                                1801
                        )
                )
        );
    }

    @Test
    void rejectsInvalidLogCount(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "LOG",
                                "workspaceRoot",
                                tempDir.toString(),
                                "repositoryPath",
                                "repo",
                                "count",
                                0
                        )
                )
        );
    }

    @Test
    void rejectsExcessiveLogCount(
            @TempDir Path tempDir) {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "operation",
                                "LOG",
                                "workspaceRoot",
                                tempDir.toString(),
                                "repositoryPath",
                                "repo",
                                "count",
                                101
                        )
                )
        );
    }

    @Test
    void rejectsMissingRepositoryDirectory(
            @TempDir Path tempDir) {

        PluginContext context = context(
                Map.of(
                        "operation",
                        "STATUS",
                        "workspaceRoot",
                        tempDir.toString(),
                        "repositoryPath",
                        "missing"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsNonGitRepository(
            @TempDir Path tempDir)
            throws Exception {

        Path directory =
                tempDir.resolve("not-repo");

        Files.createDirectories(directory);

        PluginContext context = context(
                Map.of(
                        "operation",
                        "STATUS",
                        "workspaceRoot",
                        tempDir.toString(),
                        "repositoryPath",
                        "not-repo"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    @Test
    void rejectsExistingCloneDestination(
            @TempDir Path tempDir)
            throws Exception {

        Path destination =
                tempDir.resolve("existing");

        Files.createDirectories(destination);

        PluginContext context = context(
                Map.of(
                        "operation",
                        "CLONE",
                        "repository",
                        "https://github.com/example/test.git",
                        "workspaceRoot",
                        tempDir.toString(),
                        "destinationPath",
                        "existing"
                )
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Path createRepository(
            Path tempDir)
            throws Exception {

        Path repository =
                tempDir.resolve("repo");

        Files.createDirectories(repository);

        runGit(
                repository,
                "init"
        );

        runGit(
                repository,
                "config",
                "user.email",
                "test@example.com"
        );

        runGit(
                repository,
                "config",
                "user.name",
                "Conduit Test"
        );

        Files.writeString(
                repository.resolve("initial.txt"),
                "initial"
        );

        runGit(
                repository,
                "add",
                "."
        );

        runGit(
                repository,
                "commit",
                "-m",
                "initial commit"
        );

        return repository;
    }

    private String runGit(
            Path directory,
            String... arguments)
            throws Exception {

        var command =
                new java.util.ArrayList<String>();

        command.add("git");
        command.add("-C");
        command.add(directory.toString());

        command.addAll(
                java.util.List.of(arguments)
        );

        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();

        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8
                );

        int exitCode =
                process.waitFor();

        assertEquals(
                0,
                exitCode,
                "Git command failed: "
                        + String.join(
                        " ",
                        command
                )
                        + "\n"
                        + output
        );

        return output;
    }

    private PluginContext context(
            Map<String, Object> configuration) {

        return new PluginContext(
                "git-test",
                30,
                configuration,
                Map.of()
        );
    }
}