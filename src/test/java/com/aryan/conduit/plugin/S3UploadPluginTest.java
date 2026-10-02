package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3UploadPluginTest {

    private S3Client s3Client;
    private S3UploadPlugin plugin;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        plugin = new S3UploadPlugin(s3Client);
    }

    @Test
    void metadataIsCorrect() {

        var metadata = plugin.metadata();

        assertEquals("S3_UPLOAD", metadata.type());
        assertEquals("S3 Upload", metadata.displayName());
        assertEquals("1.0.0", metadata.version());
        assertNotNull(metadata.configurationSchema());
    }

    @Test
    void missingConfigurationIsRejected() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(null)
        );
    }

    @Test
    void missingBucketIsRejected() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "key", "test.txt",
                                "content", "hello"
                        )
                )
        );
    }

    @Test
    void missingKeyIsRejected() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "bucket", "test-bucket",
                                "content", "hello"
                        )
                )
        );
    }

    @Test
    void missingSourceIsRejected() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "bucket", "test-bucket",
                                "key", "test.txt"
                        )
                )
        );
    }

    @Test
    void contentAndFileCannotBothBeConfigured() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "bucket", "test-bucket",
                                "key", "test.txt",
                                "content", "hello",
                                "filePath", "test.txt"
                        )
                )
        );
    }

    @Test
    void blankContentTypeIsRejected() {

        assertThrows(
                PluginConfigurationException.class,
                () -> plugin.validateConfiguration(
                        Map.of(
                                "bucket", "test-bucket",
                                "key", "test.txt",
                                "content", "hello",
                                "contentType", " "
                        )
                )
        );
    }

    @Test
    void uploadsTextContent() {

        when(s3Client.putObject(
                any(PutObjectRequest.class),
                any(RequestBody.class)
        )).thenReturn(
                PutObjectResponse.builder()
                        .eTag("test-etag")
                        .build()
        );

        PluginContext context = new PluginContext(
                "upload-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "artifacts/test.txt",
                        "content", "hello world",
                        "contentType", "text/plain"
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertEquals(
                "test-bucket",
                result.getMetadata().get("bucket")
        );

        assertEquals(
                "artifacts/test.txt",
                result.getMetadata().get("key")
        );

        assertEquals(
                "test-etag",
                result.getMetadata().get("etag")
        );

        verify(s3Client).putObject(
                any(PutObjectRequest.class),
                any(RequestBody.class)
        );
    }

    @Test
    void uploadsFile(@TempDir Path tempDir) throws Exception {

        Path file = tempDir.resolve("result.txt");

        Files.writeString(
                file,
                "workflow result"
        );

        when(s3Client.putObject(
                any(PutObjectRequest.class),
                any(RequestBody.class)
        )).thenReturn(
                PutObjectResponse.builder()
                        .eTag("file-etag")
                        .build()
        );

        PluginContext context = new PluginContext(
                "upload-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "results/result.txt",
                        "filePath", file.toString()
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertTrue(result.isSuccess());

        assertEquals(
                "test-bucket",
                result.getMetadata().get("bucket")
        );

        assertEquals(
                "results/result.txt",
                result.getMetadata().get("key")
        );

        assertEquals(
                "file-etag",
                result.getMetadata().get("etag")
        );

        verify(s3Client).putObject(
                any(PutObjectRequest.class),
                any(RequestBody.class)
        );
    }

    @Test
    void missingFileIsRejected(@TempDir Path tempDir) {

        Path file = tempDir.resolve("missing.txt");

        PluginContext context = new PluginContext(
                "upload-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "results/result.txt",
                        "filePath", file.toString()
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
    void s3FailureReturnsFailedPluginResult() {

        when(s3Client.putObject(
                any(PutObjectRequest.class),
                any(RequestBody.class)
        )).thenThrow(
                new RuntimeException("S3 unavailable")
        );

        PluginContext context = new PluginContext(
                "upload-task",
                30,
                Map.of(
                        "bucket", "test-bucket",
                        "key", "test.txt",
                        "content", "hello"
                ),
                Map.of()
        );

        var result = plugin.execute(context);

        assertFalse(result.isSuccess());

        assertTrue(
                result.getOutput().contains("S3 unavailable")
        );
    }
}