package com.aryan.conduit.plugin;

import com.aryan.conduit.execution.workspace.ExecutionWorkspace;
import com.aryan.conduit.execution.workspace.ExecutionWorkspaceService;
import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3DownloadPluginTest {

    private static final String CONTENT = "Conduit AWS round-trip test successful!";
    private S3Client s3Client;
    private S3DownloadPlugin plugin;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        plugin = new S3DownloadPlugin(s3Client);
    }

    @Test
    void streamsToNestedManagedWorkspaceAndPublishesAtomically() throws Exception {
        ExecutionWorkspace workspace = workspace("1", 1024);
        mockDownload(CONTENT);

        var result = plugin.execute(context(workspace, "working/aws-roundtrip/downloaded.txt", Map.of()));

        Path downloaded = workspace.resolve("working/aws-roundtrip/downloaded.txt");
        assertTrue(result.isSuccess(), result.getOutput());
        assertEquals(CONTENT, Files.readString(downloaded));
        assertEquals((long) CONTENT.length(), result.getMetadata().get("sizeBytes"));
        assertEquals("text/plain", result.getMetadata().get("contentType"));
        try (var files = Files.walk(workspace.root())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".partial")));
        }
        verify(s3Client).getObject(any(GetObjectRequest.class));
        verify(s3Client, never()).headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class));
    }

    @Test
    void enforcesQuotaAndDoesNotPublishOversizedObject() throws Exception {
        ExecutionWorkspace workspace = workspace("2", 8);
        mockDownload(CONTENT);

        var result = plugin.execute(context(workspace, "working/out.txt", Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("size limit"));
        assertFalse(Files.exists(workspace.resolve("working/out.txt")));
        assertNoPartialFiles(workspace);
    }

    @Test
    void failedStreamLeavesNoFinalFileOrPartialFile() throws Exception {
        ExecutionWorkspace workspace = workspace("3", 1024);
        byte[] prefix = "partial".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        InputStream failing = new InputStream() {
            private boolean emitted;
            @Override public int read() throws IOException {
                if (!emitted) { emitted = true; return prefix[0]; }
                throw new IOException("stream interrupted");
            }
        };
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(response(failing, 20));

        var result = plugin.execute(context(workspace, "working/out.txt", Map.of()));

        assertFalse(result.isSuccess());
        assertFalse(Files.exists(workspace.resolve("working/out.txt")));
        assertNoPartialFiles(workspace);
    }

    @Test
    void contentLengthMismatchIsRejectedBeforeAtomicPublication() throws Exception {
        ExecutionWorkspace workspace = workspace("31", 1024);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(
                response(new ByteArrayInputStream("short".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 30));

        var result = plugin.execute(context(workspace, "working/out.txt", Map.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("length did not match"));
        assertFalse(Files.exists(workspace.resolve("working/out.txt")));
        assertNoPartialFiles(workspace);
    }

    @Test
    void rejectsTraversalBeforeCallingS3() throws Exception {
        ExecutionWorkspace workspace = workspace("4", 1024);
        assertThrows(PluginConfigurationException.class,
                () -> plugin.execute(context(workspace, "working/../../outside.txt", Map.of())));
        verifyNoInteractions(s3Client);
    }

    @Test
    void rejectsSymlinkEscape() throws Exception {
        ExecutionWorkspace workspace = workspace("5", 1024);
        Path outside = temporaryDirectory.resolve("outside");
        Files.writeString(outside, "outside");
        Path link = workspace.root().resolve("working");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable: " + ex.getMessage());
        }
        var result = plugin.execute(context(workspace, "working/out.txt", Map.of()));
        assertFalse(result.isSuccess());
        verifyNoInteractions(s3Client);
    }

    @Test
    void failedS3RequestDoesNotLeaveFinalDestination() throws Exception {
        ExecutionWorkspace workspace = workspace("6", 1024);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(
                S3Exception.builder().statusCode(404).message("missing object").build());

        var result = plugin.execute(context(workspace, "working/out.txt", Map.of()));

        assertFalse(result.isSuccess());
        assertFalse(Files.exists(workspace.resolve("working/out.txt")));
        assertNoPartialFiles(workspace);
    }

    @Test
    void overwriteFalsePreservesExistingDestination() throws Exception {
        ExecutionWorkspace workspace = workspace("7", 1024);
        Path destination = workspace.resolve("working/out.txt");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, "old");
        mockDownload("new");

        var result = plugin.execute(context(workspace, "working/out.txt", Map.of("overwrite", false)));

        assertFalse(result.isSuccess());
        assertEquals("old", Files.readString(destination));
    }

    @TempDir Path temporaryDirectory;

    private ExecutionWorkspace workspace(String id, long maxBytes) throws Exception {
        return new ExecutionWorkspaceService(temporaryDirectory.resolve(id).toString(), maxBytes).forExecution(id);
    }

    private PluginContext context(ExecutionWorkspace workspace, String destination, Map<String, Object> extra) {
        var configuration = new java.util.HashMap<String, Object>();
        configuration.put("bucket", "test-bucket");
        configuration.put("key", "object.txt");
        configuration.put("workspaceRoot", workspace.root().toString());
        configuration.put("destinationPath", destination);
        configuration.putAll(extra);
        return new PluginContext("download-task", 30, configuration, Map.of(), workspace);
    }

    private void mockDownload(String content) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(response(new ByteArrayInputStream(bytes), bytes.length));
    }

    private ResponseInputStream<GetObjectResponse> response(InputStream body, long contentLength) {
        GetObjectResponse response = GetObjectResponse.builder().contentLength(contentLength)
                .contentType("text/plain").eTag("test-etag").build();
        return new ResponseInputStream<>(response, AbortableInputStream.create(body, () -> { }));
    }

    private void assertNoPartialFiles(ExecutionWorkspace workspace) throws IOException {
        try (var files = Files.walk(workspace.root())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".partial")));
        }
    }
}
