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
import java.nio.file.Files;
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
        String workspaceRootValue =
                String.valueOf(configuration.get("workspaceRoot"));
        String destinationPath =
                String.valueOf(configuration.get("destinationPath"));

        boolean createDirectories =
                getBoolean(configuration, "createDirectories", true);

        Path workspaceRoot = Paths.get(workspaceRootValue)
                .toAbsolutePath()
                .normalize();

        if (!Files.exists(workspaceRoot)) {
            if (createDirectories) {
                Files.createDirectories(workspaceRoot);
            } else {
                throw new PluginConfigurationException(
                        "S3_DOWNLOAD workspace root does not exist: "
                                + workspaceRoot
                );
            }
        }

        if (!Files.isDirectory(workspaceRoot)) {
            throw new PluginConfigurationException(
                    "S3_DOWNLOAD workspaceRoot is not a directory: "
                            + workspaceRoot
            );
        }

        Path destination = resolveDestination(
                workspaceRoot,
                destinationPath
        );

        prepareDestination(
                workspaceRoot,
                destination,
                createDirectories
        );

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        try {
            /*
             * AWS SDK v2 direct-to-file overload.
             *
             * The object is streamed directly to the destination file,
             * avoiding an in-memory byte[] for potentially large objects.
             */
            GetObjectResponse response = s3Client.getObject(
                    request,
                    destination
            );

            long sizeBytes = Files.size(destination);

            Map<String, Object> metadata = new LinkedHashMap<>();

            metadata.put("operation", "DOWNLOAD");
            metadata.put("bucket", bucket);
            metadata.put("key", key);
            metadata.put("destinationPath", destination.toString());
            metadata.put("sizeBytes", sizeBytes);

            if (response.contentLength() != null) {
                metadata.put(
                        "contentLength",
                        response.contentLength()
                );
            }

            if (response.contentType() != null) {
                metadata.put(
                        "contentType",
                        response.contentType()
                );
            }

            if (response.eTag() != null) {
                metadata.put(
                        "etag",
                        response.eTag()
                );
            }

            return PluginResult.builder()
                    .success(true)
                    .output("Object downloaded successfully")
                    .metadata(metadata)
                    .build();

        } catch (S3Exception ex) {

            cleanupPartialFile(destination);

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
                            "destinationPath", destination.toString()
                    ))
                    .build();

        } catch (Exception ex) {

            cleanupPartialFile(destination);

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
                            "destinationPath", destination.toString()
                    ))
                    .build();
        }
    }

    private Path resolveDestination(
            Path workspaceRoot,
            String destinationPath) {

        Path resolved = workspaceRoot
                .resolve(destinationPath)
                .normalize();

        if (!resolved.startsWith(workspaceRoot)) {
            throw new PluginConfigurationException(
                    "S3_DOWNLOAD destination escapes workspace root"
            );
        }

        return resolved;
    }

    private void prepareDestination(
            Path workspaceRoot,
            Path destination,
            boolean createDirectories) throws IOException {

        if (Files.exists(destination) && Files.isDirectory(destination)) {
            throw new PluginConfigurationException(
                    "S3_DOWNLOAD destination is a directory: "
                            + destination
            );
        }

        Path parent = destination.getParent();

        if (parent == null) {
            throw new PluginConfigurationException(
                    "S3_DOWNLOAD destination must be inside workspace root"
            );
        }

        if (!parent.startsWith(workspaceRoot)) {
            throw new PluginConfigurationException(
                    "S3_DOWNLOAD destination escapes workspace root"
            );
        }

        if (createDirectories) {
            Files.createDirectories(parent);
        } else if (!Files.exists(parent)) {
            throw new PluginConfigurationException(
                    "S3_DOWNLOAD parent directory does not exist: "
                            + parent
            );
        }

        if (Files.exists(parent) && !Files.isDirectory(parent)) {
            throw new PluginConfigurationException(
                    "S3_DOWNLOAD destination parent is not a directory: "
                            + parent
            );
        }
    }

    private void validateRelativePath(String path) {

        if (path == null || path.isBlank()) {
            throw new PluginConfigurationException(
                    "S3_DOWNLOAD 'destinationPath' is required"
            );
        }

        Path parsed;

        try {
            parsed = Paths.get(path);
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

    private void cleanupPartialFile(Path destination) {

        try {

            if (Files.exists(destination)
                    && Files.isRegularFile(destination)) {

                Files.deleteIfExists(destination);
            }

        } catch (IOException ignored) {
            /*
             * Preserve the original S3/download failure.
             */
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
}