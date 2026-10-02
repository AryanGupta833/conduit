package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilePluginTest {

    @TempDir
    Path workspace;

    private final FileReadPlugin readPlugin =
            new FileReadPlugin();

    private final FileWritePlugin writePlugin =
            new FileWritePlugin();

    @Test
    void fileReadExposesMetadata() {

        assertEquals(
                "FILE_READ",
                readPlugin.metadata().type()
        );

        assertEquals(
                "File Read",
                readPlugin.metadata().displayName()
        );
    }

    @Test
    void fileWriteExposesMetadata() {

        assertEquals(
                "FILE_WRITE",
                writePlugin.metadata().type()
        );

        assertEquals(
                "File Write",
                writePlugin.metadata().displayName()
        );
    }

    @Test
    void fileReadRejectsAbsolutePath() {

        Map<String, Object> configuration =
                Map.of(
                        "workspaceRoot",
                        workspace.toString(),
                        "path",
                        workspace.resolve("secret.txt").toString()
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> readPlugin.validateConfiguration(configuration)
        );
    }

    @Test
    void fileReadRejectsTraversalPath() {

        Map<String, Object> configuration =
                Map.of(
                        "workspaceRoot",
                        workspace.toString(),
                        "path",
                        "../secret.txt"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> readPlugin.validateConfiguration(configuration)
        );
    }

    @Test
    void fileWriteRejectsAbsolutePath() {

        Map<String, Object> configuration =
                Map.of(
                        "workspaceRoot",
                        workspace.toString(),
                        "path",
                        workspace.resolve("output.txt").toString(),
                        "content",
                        "hello"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> writePlugin.validateConfiguration(configuration)
        );
    }

    @Test
    void fileWriteRejectsTraversalPath() {

        Map<String, Object> configuration =
                Map.of(
                        "workspaceRoot",
                        workspace.toString(),
                        "path",
                        "../output.txt",
                        "content",
                        "hello"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> writePlugin.validateConfiguration(configuration)
        );
    }

    @Test
    void fileWriteAndReadRoundTrip() throws Exception {

        Map<String, Object> writeConfiguration =
                Map.of(
                        "workspaceRoot",
                        workspace.toString(),
                        "path",
                        "output/result.txt",
                        "content",
                        "Hello from Conduit"
                );

        var writeResult =
                writePlugin.execute(
                        new PluginContext(
                                "file-write",
                                30,
                                writeConfiguration,
                                Map.of()
                        )
                );

        assertTrue(writeResult.isSuccess());

        Path writtenFile =
                workspace
                        .resolve("output")
                        .resolve("result.txt");

        assertTrue(
                Files.exists(writtenFile)
        );

        Map<String, Object> readConfiguration =
                Map.of(
                        "workspaceRoot",
                        workspace.toString(),
                        "path",
                        "output/result.txt"
                );

        var readResult =
                readPlugin.execute(
                        new PluginContext(
                                "file-read",
                                30,
                                readConfiguration,
                                Map.of()
                        )
                );

        assertTrue(readResult.isSuccess());

        assertEquals(
                "Hello from Conduit",
                readResult.output()
        );
    }

    @Test
    void fileWriteCanPreventOverwrite() {

        Map<String, Object> configuration =
                Map.of(
                        "workspaceRoot",
                        workspace.toString(),
                        "path",
                        "result.txt",
                        "content",
                        "first"
                );

        var first =
                writePlugin.execute(
                        new PluginContext(
                                "file-write",
                                30,
                                configuration,
                                Map.of()
                        )
                );

        assertTrue(first.isSuccess());

        var second =
                writePlugin.execute(
                        new PluginContext(
                                "file-write",
                                30,
                                Map.of(
                                        "workspaceRoot",
                                        workspace.toString(),
                                        "path",
                                        "result.txt",
                                        "content",
                                        "second",
                                        "overwrite",
                                        false
                                ),
                                Map.of()
                        )
                );

        assertFalse(second.isSuccess());
    }

    @Test
    void fileWriteCreatesParentDirectories() {

        var result =
                writePlugin.execute(
                        new PluginContext(
                                "file-write",
                                30,
                                Map.of(
                                        "workspaceRoot",
                                        workspace.toString(),
                                        "path",
                                        "nested/deep/result.txt",
                                        "content",
                                        "test"
                                ),
                                Map.of()
                        )
                );

        assertTrue(result.isSuccess());

        assertTrue(
                Files.exists(
                        workspace
                                .resolve("nested")
                                .resolve("deep")
                                .resolve("result.txt")
                )
        );
    }

    @Test
    void fileReadReturnsFailureForMissingFile() {

        var result =
                readPlugin.execute(
                        new PluginContext(
                                "file-read",
                                30,
                                Map.of(
                                        "workspaceRoot",
                                        workspace.toString(),
                                        "path",
                                        "does-not-exist.txt"
                                ),
                                Map.of()
                        )
                );

        assertFalse(result.isSuccess());
    }

    @Test
    void fileWriteRejectsMissingContent() {

        Map<String, Object> configuration =
                Map.of(
                        "workspaceRoot",
                        workspace.toString(),
                        "path",
                        "result.txt"
                );

        assertThrows(
                PluginConfigurationException.class,
                () -> writePlugin.validateConfiguration(configuration)
        );
    }
}