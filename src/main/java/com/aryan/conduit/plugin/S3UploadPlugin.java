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
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;

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
    private static final long MULTIPART_THRESHOLD = 64L * 1024 * 1024;
    private static final long MULTIPART_PART_SIZE = 16L * 1024 * 1024;

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
                                ),
                                "metadata", Map.of("type", "object"),
                                "tags", Map.of("type", "object"),
                                "ifAbsent", Map.of("type", "boolean", "default", false)
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
        if (configuration.get("metadata") != null && !(configuration.get("metadata") instanceof Map<?, ?>)) throw new PluginConfigurationException("S3_UPLOAD metadata must be an object");
        if (configuration.get("tags") != null && !(configuration.get("tags") instanceof Map<?, ?>)) throw new PluginConfigurationException("S3_UPLOAD tags must be an object");
        if (configuration.get("ifAbsent") != null && !(configuration.get("ifAbsent") instanceof Boolean)) throw new PluginConfigurationException("S3_UPLOAD ifAbsent must be boolean");
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
            Map<String, String> userMetadata = stringMap(configuration.get("metadata"));
            Map<String, String> tags = stringMap(configuration.get("tags"));
            if (userMetadata.size() > 20 || tags.size() > 10) throw new PluginConfigurationException("S3_UPLOAD supports at most 20 metadata fields and 10 tags");
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .metadata(userMetadata)
                    .tagging(encodeTags(tags))
                    .ifNoneMatch(Boolean.TRUE.equals(configuration.get("ifAbsent")) ? "*" : null)
                    .build();

            PutObjectResponse response;
            long sizeBytes;

            if (configuration.get("filePath") != null) {

                Path filePath = Paths.get(
                        String.valueOf(
                                configuration.get("filePath")
                        )
                ).toAbsolutePath().normalize();

                validateFile(filePath);
                sizeBytes = Files.size(filePath);
                userMetadata.put("conduit-sha256", sha256(filePath));
                request = request.toBuilder().metadata(userMetadata).build();
                if (context.workspace() != null && context.workspace().usedBytes() > context.workspace().maximumBytes())
                    throw new PluginConfigurationException("Execution workspace is already over its size limit");
                long threshold = configuration.get("multipartThresholdBytes") instanceof Number n ? n.longValue() : MULTIPART_THRESHOLD;
                long partSize = configuration.get("partSizeBytes") instanceof Number n ? n.longValue() : MULTIPART_PART_SIZE;
                if (partSize < 5L * 1024 * 1024 || partSize > 5L * 1024 * 1024 * 1024)
                    throw new PluginConfigurationException("S3 multipart partSizeBytes must be between 5 MiB and 5 GiB");
                if (sizeBytes >= threshold) {
                    String multipartEtag = multipartUpload(request, filePath, partSize);
                    response = PutObjectResponse.builder().eTag(multipartEtag).build();
                } else response = s3Client.putObject(request.toBuilder().checksumSHA256(userMetadata.get("conduit-sha256")).build(), RequestBody.fromFile(filePath));

            } else {

                byte[] content =
                        String.valueOf(
                                configuration.get("content")
                        ).getBytes(StandardCharsets.UTF_8);
                sizeBytes = content.length;
                String checksum = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(content));
                userMetadata.put("conduit-sha256", checksum);

                request = request.toBuilder()
                        .contentLength((long) content.length)
                        .metadata(userMetadata)
                        .checksumSHA256(checksum)
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
                            "sizeBytes", sizeBytes,
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

    private String multipartUpload(PutObjectRequest request, Path file, long partSize) throws Exception {
        long size = Files.size(file);
        long count = (size + partSize - 1) / partSize;
        if (count > 10_000) throw new PluginConfigurationException("S3 multipart upload exceeds 10000 parts");
        var create = CreateMultipartUploadRequest.builder().bucket(request.bucket()).key(request.key())
                .contentType(request.contentType()).metadata(request.metadata()).tagging(request.tagging()).build();
        String uploadId = s3Client.createMultipartUpload(create).uploadId();
        try {
            List<CompletedPart> parts = new java.util.ArrayList<>();
            for (int part = 1; part <= count; part++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("S3 upload cancelled");
                long offset = (long) (part - 1) * partSize;
                long length = Math.min(partSize, size - offset);
                software.amazon.awssdk.http.ContentStreamProvider provider = () -> {
                    try {
                        var stream = Files.newInputStream(file);
                        stream.skipNBytes(offset);
                        return new BoundedInputStream(stream, length);
                    } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                };
                var body = RequestBody.fromContentProvider(provider, length, DEFAULT_CONTENT_TYPE);
                var result = s3Client.uploadPart(UploadPartRequest.builder().bucket(request.bucket()).key(request.key())
                        .uploadId(uploadId).partNumber(part).build(), body);
                parts.add(CompletedPart.builder().partNumber(part).eTag(result.eTag()).build());
            }
            return s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(request.bucket()).key(request.key())
                    .uploadId(uploadId).ifNoneMatch(request.ifNoneMatch())
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()).build()).eTag();
        } catch (Exception e) {
            try { s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(request.bucket()).key(request.key()).uploadId(uploadId).build()); }
            catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private Map<String, String> stringMap(Object value) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((k, v) -> {
            if (!(k instanceof String key) || !(v instanceof String text)) throw new PluginConfigurationException("S3 metadata and tags must contain string keys and values");
            result.put(key, text);
        });
        return result;
    }
    private String encodeTags(Map<String, String> tags) {
        return tags.entrySet().stream().map(e -> java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    private String sha256(Path file) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) >= 0) digest.update(b, 0, n); }
        return java.util.Base64.getEncoder().encodeToString(digest.digest());
    }

    private static final class BoundedInputStream extends java.io.FilterInputStream {
        private long remaining;
        BoundedInputStream(java.io.InputStream input, long length) { super(input); this.remaining = length; }
        @Override public int read() throws java.io.IOException {
            if (remaining <= 0) return -1;
            int value = super.read(); if (value >= 0) remaining--; return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            if (remaining <= 0) return -1;
            int count = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
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
