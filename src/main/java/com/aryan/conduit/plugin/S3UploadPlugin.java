package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class S3UploadPlugin implements WorkflowPlugin {

    private static final String DEFAULT_CONTENT_TYPE =
            "application/octet-stream";

    private final S3Client s3Client;

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata(
                "S3_UPLOAD",
                "S3 Upload",
                "Upload a file or text content to Amazon S3.",
                "1.0.0",
                Map.of(
                        "type", "object",
                        "required", List.of("bucket", "key"),
                        "properties", Map.of(
                                "bucket", Map.of(
                                        "type", "string",
                                        "description", "Target S3 bucket"
                                ),
                                "key", Map.of(
                                        "type", "string",
                                        "description", "Destination S3 object key"
                                ),
                                "filePath", Map.of(
                                        "type", "string",
                                        "description",
                                        "Local file to upload"
                                ),
                                "content", Map.of(
                                        "type", "string",
                                        "description",
                                        "Text content to upload"
                                ),
                                "contentType", Map.of(
                                        "type", "string",
                                        "default", DEFAULT_CONTENT_TYPE,
                                        "description",
                                        "S3 object content type"
                                )
                        )
                )
        );
    }

    @Override
    public void validateConfiguration(
            Map<String, Object> configuration)
            throws PluginConfigurationException {

        if (configuration == null) {
            throw new PluginConfigurationException(
                    "S3_UPLOAD configuration is required"
            );
        }

        requireNonBlank(
                configuration,
                "bucket",
                "S3_UPLOAD requires a non-empty 'bucket'"
        );

        requireNonBlank(
                configuration,
                "key",
                "S3_UPLOAD requires a non-empty 'key'"
        );

        boolean hasFilePath =
                configuration.get("filePath") != null
                        && !String.valueOf(
                        configuration.get("filePath")
                ).isBlank();

        boolean hasContent =
                configuration.containsKey("content")
                        && configuration.get("content") != null;

        if (!hasFilePath && !hasContent) {
            throw new PluginConfigurationException(
                    "S3_UPLOAD requires either 'filePath' or 'content'"
            );
        }

        if (hasFilePath && hasContent) {
            throw new PluginConfigurationException(
                    "S3_UPLOAD cannot use both 'filePath' and 'content'"
            );
        }

        Object contentType = configuration.get("contentType");

        if (contentType != null
                && (!(contentType instanceof String string)
                || string.isBlank())) {

            throw new PluginConfigurationException(
                    "S3_UPLOAD 'contentType' must be a non-empty string"
            );
        }
    }

    @Override
    public PluginResult execute(PluginContext context) {

        validateConfiguration(context.configuration());

        Map<String, Object> configuration =
                context.configuration();

        String bucket =
                String.valueOf(configuration.get("bucket"));

        String key =
                String.valueOf(configuration.get("key"));

        String contentType =
                configuration.get("contentType") == null
                        ? DEFAULT_CONTENT_TYPE
                        : String.valueOf(
                        configuration.get("contentType")
                );

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build();

            PutObjectResponse response;

            if (configuration.get("filePath") != null) {

                Path filePath = Paths.get(
                        String.valueOf(
                                configuration.get("filePath")
                        )
                ).toAbsolutePath().normalize();

                validateFile(filePath);

                response = s3Client.putObject(
                        request,
                        RequestBody.fromFile(filePath)
                );

            } else {

                byte[] content =
                        String.valueOf(
                                configuration.get("content")
                        ).getBytes(StandardCharsets.UTF_8);

                request = request.toBuilder()
                        .contentLength((long) content.length)
                        .build();

                response = s3Client.putObject(
                        request,
                        RequestBody.fromBytes(content)
                );
            }

            return PluginResult.builder()
                    .success(true)
                    .output("Object uploaded successfully")
                    .metadata(Map.of(
                            "operation", "UPLOAD",
                            "bucket", bucket,
                            "key", key,
                            "contentType", contentType,
                            "etag", response.eTag() == null
                                    ? ""
                                    : response.eTag()
                    ))
                    .build();

        } catch (PluginConfigurationException ex) {
            throw ex;

        } catch (Exception ex) {

            String error = ex.getMessage() == null
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage();

            return PluginResult.builder()
                    .success(false)
                    .output(error)
                    .metadata(Map.of(
                            "operation", "UPLOAD",
                            "bucket", bucket,
                            "key", key
                    ))
                    .build();
        }
    }

    private void validateFile(Path filePath) {

        if (!Files.exists(filePath)) {
            throw new PluginConfigurationException(
                    "S3_UPLOAD file does not exist: "
                            + filePath
            );
        }

        if (!Files.isRegularFile(filePath)) {
            throw new PluginConfigurationException(
                    "S3_UPLOAD path is not a regular file: "
                            + filePath
            );
        }

        if (!Files.isReadable(filePath)) {
            throw new PluginConfigurationException(
                    "S3_UPLOAD file is not readable: "
                            + filePath
            );
        }
    }

    private void requireNonBlank(
            Map<String, Object> configuration,
            String key,
            String message) {

        Object value = configuration.get(key);

        if (!(value instanceof String string)
                || string.isBlank()) {

            throw new PluginConfigurationException(message);
        }
    }
}