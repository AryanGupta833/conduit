package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class S3DownloadPlugin implements WorkflowPlugin {

    private final S3Client s3Client;

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of(
                "S3_DOWNLOAD",
                "S3 Download",
                "Downloads an object from Amazon S3 directly to a workspace file",
                "1.0.0"
        );
    }

    @Override
    public void validateConfiguration(Map<String, Object> configuration)
            throws PluginConfigurationException {

        if (configuration == null) {
            throw new PluginConfigurationException(
                    "S3_DOWNLOAD configuration is required"
            );
        }

        requireString(configuration, "bucket");
        requireString(configuration, "key");
        requireString(configuration, "workspaceRoot");
        requireString(configuration, "destinationPath");

        String destinationPath =
                String.valueOf(configuration.get("destinationPath"));

        validateRelativePath(destinationPath);
    }

    @Override
    public PluginResult execute(PluginContext context) throws Exception {

        Map<String, Object> configuration = context.configuration();

        validateConfiguration(configuration);

        String bucket = String.valueOf(configuration.get("bucket"));
        String key = String.valueOf(configuration.get("key"));
        String destinationPath =
                String.valueOf(configuration.get("destinationPath"));

        boolean createDirectories =
                getBoolean(configuration, "createDirectories", true);
        if (context.workspace() == null)
            throw new PluginConfigurationException("S3_DOWNLOAD requires a managed execution workspace");
        boolean overwrite = getBoolean(configuration, "overwrite", true);

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .versionId(configuration.get("versionId") == null ? null : String.valueOf(configuration.get("versionId")))
                .build();

        try (var responseBody = s3Client.getObject(request)) {
            GetObjectResponse response = responseBody.response();
            java.io.InputStream verifiedBody = response.contentLength() == null
                    ? responseBody : new ContentLengthVerifyingInputStream(responseBody, response.contentLength());
            long sizeBytes = context.workspace().writeAtomic(
                    destinationPath, verifiedBody, overwrite, createDirectories);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("operation", "DOWNLOAD");
            metadata.put("bucket", bucket);
            metadata.put("key", key);
            metadata.put("destinationPath", destinationPath);
            metadata.put("sizeBytes", sizeBytes);
            if (response.contentLength() != null) {
                metadata.put("contentLength", response.contentLength());
            }
            if (response.contentType() != null) {
                metadata.put("contentType", response.contentType());
            }
            if (response.eTag() != null) {
                metadata.put("etag", response.eTag());
            }
            return PluginResult.builder().success(true).output("Object downloaded successfully").metadata(metadata).build();

        } catch (S3Exception ex) {

            return PluginResult.builder()
                    .success(false)
                    .output(
                            "S3 download failed: "
                                    + extractS3ErrorMessage(ex)
                    )
                    .metadata(Map.of(
                            "operation", "DOWNLOAD",
                            "bucket", bucket,
                            "key", key,
                            "destinationPath", destinationPath
                    ))
                    .build();

        } catch (Exception ex) {

            return PluginResult.builder()
                    .success(false)
                    .output(
                            "S3 download failed: "
                                    + (ex.getMessage() == null
                                    ? ex.getClass().getSimpleName()
                                    : ex.getMessage())
                    )
                    .metadata(Map.of(
                            "operation", "DOWNLOAD",
                            "bucket", bucket,
                            "key", key,
                            "destinationPath", destinationPath
                    ))
                    .build();
        }
    }

    private void validateRelativePath(String path) {

        if (path == null || path.isBlank()) {
            throw new PluginConfigurationException(
                    "S3_DOWNLOAD 'destinationPath' is required"
            );
        }

        String parsedValue = path;
        if (parsedValue.startsWith("workspace:/")) {
            parsedValue = parsedValue.substring("workspace:/".length());
            if (parsedValue.isBlank())
                throw new PluginConfigurationException("S3_DOWNLOAD destinationPath must name a file");
        }

        Path parsed;

        try {
            parsed = Paths.get(parsedValue);
        } catch (Exception ex) {
            throw new PluginConfigurationException(
                    "S3_DOWNLOAD 'destinationPath' is invalid"
            );
        }

        /*
         * Reject absolute paths and Windows drive/UNC style paths.
         */
        if (parsed.isAbsolute()
                || path.startsWith("/")
                || path.startsWith("\\")
                || path.contains(":")) {

            throw new PluginConfigurationException(
                    "S3_DOWNLOAD 'destinationPath' must be relative"
            );
        }

        /*
         * Explicitly reject traversal segments.
         *
         * normalize() alone would technically prevent escaping the
         * workspace when combined with startsWith(), but rejecting ".."
         * completely makes the plugin's security contract clearer.
         */
        for (Path segment : parsed) {

            if ("..".equals(segment.toString())) {

                throw new PluginConfigurationException(
                        "S3_DOWNLOAD 'destinationPath' cannot contain "
                                + "'..' traversal segments"
                );
            }
        }
    }

    private String requireString(
            Map<String, Object> configuration,
            String key) {

        Object value = configuration.get(key);

        if (value == null
                || value.toString().isBlank()) {

            throw new PluginConfigurationException(
                    "S3_DOWNLOAD '" + key + "' is required"
            );
        }

        return value.toString();
    }

    private boolean getBoolean(
            Map<String, Object> configuration,
            String key,
            boolean defaultValue) {

        Object value = configuration.get(key);

        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        if (value instanceof String stringValue) {

            if ("true".equalsIgnoreCase(stringValue)) {
                return true;
            }

            if ("false".equalsIgnoreCase(stringValue)) {
                return false;
            }
        }

        throw new PluginConfigurationException(
                "S3_DOWNLOAD '" + key + "' must be a boolean"
        );
    }

    private String extractS3ErrorMessage(S3Exception ex) {

        if (ex.awsErrorDetails() != null
                && ex.awsErrorDetails().errorMessage() != null
                && !ex.awsErrorDetails().errorMessage().isBlank()) {

            return ex.awsErrorDetails().errorMessage();
        }

        if (ex.getMessage() != null
                && !ex.getMessage().isBlank()) {

            return ex.getMessage();
        }

        return ex.getClass().getSimpleName();
    }

    /** Fails before writeAtomic publishes if the S3 body length differs from its metadata. */
    private static final class ContentLengthVerifyingInputStream extends java.io.FilterInputStream {
        private final long expected;
        private long received;

        private ContentLengthVerifyingInputStream(java.io.InputStream input, long expected) {
            super(input);
            this.expected = expected;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value < 0) verifyEnd();
            else received++;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count < 0) verifyEnd();
            else if (count > 0) {
                received += count;
                if (received > expected)
                    throw new IOException("S3 download exceeded response content length");
            }
            return count;
        }

        private void verifyEnd() throws IOException {
            if (received != expected)
                throw new IOException("S3 download length did not match response metadata");
        }
    }
}
