package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class S3DownloadPluginTest {

    private S3Client s3Client;
    private S3DownloadPlugin plugin;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        plugin = new S3DownloadPlugin(s3Client);
    }

    @Test
    void downloadsObject(@TempDir Path tempDir) throws Exception {

        Path destination = tempDir.resolve("output.txt");

        when(s3Client.getObject(
                any(GetObjectRequest.class),
                eq(destination)
        )).thenAnswer(invocation -> {

            Files.writeString(
                    destination,
                    "downloaded from S3"
            );

            return GetObjectResponse.builder()
                    .eTag("download-etag")
                    .contentLength(18L)
                    .contentType("text/plain")
                    .build();
        });

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "results/output.txt",
                        "workspaceRoot", tempDir.toString(),
                        "destinationPath", "output.txt"
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertEquals(
                "downloaded from S3",
                Files.readString(destination)
        );

        assertEquals(
                "test-bucket",
                result.getMetadata().get("bucket")
        );

        assertEquals(
                "results/output.txt",
                result.getMetadata().get("key")
        );

        assertEquals(
                "download-etag",
                result.getMetadata().get("etag")
        );

        assertEquals(
                18L,
                result.getMetadata().get("contentLength")
        );

        assertEquals(
                "text/plain",
                result.getMetadata().get("contentType")
        );

        assertEquals(
                18L,
                result.getMetadata().get("sizeBytes")
        );

        verify(s3Client).getObject(
                any(GetObjectRequest.class),
                eq(destination)
        );
    }

    @Test
    void createsParentDirectories(@TempDir Path tempDir) throws Exception {

        Path destination =
                tempDir.resolve("results/data/output.txt");

        when(s3Client.getObject(
                any(GetObjectRequest.class),
                eq(destination)
        )).thenAnswer(invocation -> {

            Files.writeString(
                    destination,
                    "nested download"
            );

            return GetObjectResponse.builder()
                    .eTag("nested-etag")
                    .contentLength(15L)
                    .build();
        });

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "data/output.txt",
                        "workspaceRoot", tempDir.toString(),
                        "destinationPath", "results/data/output.txt",
                        "createDirectories", true
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertTrue(Files.exists(destination));

        assertEquals(
                "nested download",
                Files.readString(destination)
        );

        verify(s3Client).getObject(
                any(GetObjectRequest.class),
                eq(destination)
        );
    }

    @Test
    void doesNotCreateParentDirectoriesWhenDisabled(
            @TempDir Path tempDir) {

        Path destination =
                tempDir.resolve("missing/output.txt");

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "output.txt",
                        "workspaceRoot", tempDir.toString(),
                        "destinationPath", "missing/output.txt",
                        "createDirectories", false
                ),
                Map.of()
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );

        verifyNoInteractions(s3Client);
    }

    @Test
    void rejectsAbsoluteDestinationPath(
            @TempDir Path tempDir) {

        Path absolutePath =
                tempDir.resolve("output.txt").toAbsolutePath();

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "output.txt",
                        "workspaceRoot", tempDir.toString(),
                        "destinationPath", absolutePath.toString()
                ),
                Map.of()
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );

        verifyNoInteractions(s3Client);
    }

    @Test
    void rejectsParentTraversal(
            @TempDir Path tempDir) {

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "output.txt",
                        "workspaceRoot", tempDir.toString(),
                        "destinationPath", "../output.txt"
                ),
                Map.of()
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );

        verifyNoInteractions(s3Client);
    }

    @Test
    void rejectsNestedParentTraversal(
            @TempDir Path tempDir) {

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "output.txt",
                        "workspaceRoot", tempDir.toString(),
                        "destinationPath", "results/../../output.txt"
                ),
                Map.of()
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );

        verifyNoInteractions(s3Client);
    }

    @Test
    void rejectsDestinationDirectory(
            @TempDir Path tempDir) throws Exception {

        Path directory =
                tempDir.resolve("results");

        Files.createDirectories(directory);

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "output.txt",
                        "workspaceRoot", tempDir.toString(),
                        "destinationPath", "results"
                ),
                Map.of()
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );

        verifyNoInteractions(s3Client);
    }

    @Test
    void rejectsMissingRequiredConfiguration(
            @TempDir Path tempDir) {

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "workspaceRoot", tempDir.toString(),
                        "destinationPath", "output.txt"
                ),
                Map.of()
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );

        verifyNoInteractions(s3Client);
    }

    @Test
    void rejectsMissingWorkspaceRootWhenDirectoryCreationDisabled(
            @TempDir Path tempDir) {

        Path missingWorkspace =
                tempDir.resolve("does-not-exist");

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "output.txt",
                        "workspaceRoot", missingWorkspace.toString(),
                        "destinationPath", "output.txt",
                        "createDirectories", false
                ),
                Map.of()
        );

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.execute(context)
        );

        verifyNoInteractions(s3Client);
    }

    @Test
    void s3FailureReturnsFailedPluginResult(
            @TempDir Path tempDir) throws Exception {

        Path destination =
                tempDir.resolve("output.txt");

        when(s3Client.getObject(
                any(GetObjectRequest.class),
                eq(destination)
        )).thenThrow(
                S3Exception.builder()
                        .statusCode(404)
                        .message("The specified key does not exist")
                        .build()
        );

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "missing.txt",
                        "workspaceRoot", tempDir.toString(),
                        "destinationPath", "output.txt"
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertFalse(result.isSuccess());

        assertTrue(
                result.getOutput().contains(
                        "The specified key does not exist"
                )
        );

        assertEquals(
                "DOWNLOAD",
                result.getMetadata().get("operation")
        );

        assertEquals(
                "test-bucket",
                result.getMetadata().get("bucket")
        );

        assertEquals(
                "missing.txt",
                result.getMetadata().get("key")
        );

        assertFalse(Files.exists(destination));

        verify(s3Client).getObject(
                any(GetObjectRequest.class),
                eq(destination)
        );
    }

    @Test
    void cleansUpPartialFileAfterFailure(
            @TempDir Path tempDir) throws Exception {

        Path destination =
                tempDir.resolve("partial.txt");

        when(s3Client.getObject(
                any(GetObjectRequest.class),
                eq(destination)
        )).thenAnswer(invocation -> {

            // Simulate a partially downloaded file.
            Files.writeString(
                    destination,
                    "partial data"
            );

            throw S3Exception.builder()
                    .statusCode(500)
                    .message("Download interrupted")
                    .build();
        });

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "large-file.txt",
                        "workspaceRoot", tempDir.toString(),
                        "destinationPath", "partial.txt"
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertFalse(result.isSuccess());

        assertTrue(
                result.getOutput().contains(
                        "Download interrupted"
                )
        );

        assertFalse(
                Files.exists(destination),
                "Partial file should be cleaned up after failure"
        );

        verify(s3Client).getObject(
                any(GetObjectRequest.class),
                eq(destination)
        );
    }

    @Test
    void metadataContainsDestinationPath(
            @TempDir Path tempDir) throws Exception {

        Path destination =
                tempDir.resolve("results/output.txt");

        when(s3Client.getObject(
                any(GetObjectRequest.class),
                eq(destination)
        )).thenAnswer(invocation -> {

            Files.createDirectories(
                    destination.getParent()
            );

            Files.writeString(
                    destination,
                    "hello"
            );

            return GetObjectResponse.builder()
                    .contentLength(5L)
                    .build();
        });

        PluginContext context = new PluginContext(
                "download-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "hello.txt",
                        "workspaceRoot", tempDir.toString(),
                        "destinationPath", "results/output.txt"
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertEquals(
                destination.toString(),
                result.getMetadata().get("destinationPath")
        );

        assertEquals(
                5L,
                result.getMetadata().get("sizeBytes")
        );
    }
}