package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FileWritePlugin implements WorkflowPlugin {

    private static final String DEFAULT_ENCODING = "UTF-8";

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata(
                "FILE_WRITE",
                "File Write",
                "Write text content to a controlled workflow workspace.",
                "1.0.0",
                Map.of(
                        "type", "object",
                        "required", List.of(
                                "workspaceRoot",
                                "path",
                                "content"
                        ),
                        "properties", Map.of(
                                "workspaceRoot", Map.of(
                                        "type", "string",
                                        "description",
                                        "Root directory accessible to the workflow"
                                ),
                                "path", Map.of(
                                        "type", "string",
                                        "description",
                                        "Path relative to the workspace root"
                                ),
                                "content", Map.of(
                                        "type", "string",
                                        "description",
                                        "Text content to write"
                                ),
                                "encoding", Map.of(
                                        "type", "string",
                                        "default", DEFAULT_ENCODING,
                                        "description",
                                        "Text file character encoding"
                                ),
                                "overwrite", Map.of(
                                        "type", "boolean",
                                        "default", true,
                                        "description",
                                        "Whether an existing file may be replaced"
                                ),
                                "createDirectories", Map.of(
                                        "type", "boolean",
                                        "default", true,
                                        "description",
                                        "Whether missing parent directories should be created"
                                )
                        )
                )
        );
    }

    @Override
    public void validateConfiguration(
            Map<String, Object> configuration
    ) throws PluginConfigurationException {

        if (configuration == null) {
            throw new PluginConfigurationException(
                    "FILE_WRITE configuration is required"
            );
        }

        requireNonBlank(
                configuration,
                "workspaceRoot",
                "FILE_WRITE requires a non-empty 'workspaceRoot'"
        );

        requireNonBlank(
                configuration,
                "path",
                "FILE_WRITE requires a non-empty 'path'"
        );

        if (!configuration.containsKey("content")) {
            throw new PluginConfigurationException(
                    "FILE_WRITE requires 'content'"
            );
        }

        validateRelativePath(
                String.valueOf(configuration.get("path"))
        );

        validateBoolean(
                configuration,
                "overwrite"
        );

        validateBoolean(
                configuration,
                "createDirectories"
        );

        validateEncoding(configuration);
    }

    @Override
    public PluginResult execute(
            PluginContext context
    ) {

        validateConfiguration(
                context.configuration()
        );

        Map<String, Object> configuration =
                context.configuration();

        String workspaceRoot =
                String.valueOf(
                        configuration.get("workspaceRoot")
                );

        String relativePath =
                String.valueOf(
                        configuration.get("path")
                );

        String content =
                String.valueOf(
                        configuration.get("content")
                );

        String encoding =
                configuration.get("encoding") == null
                        ? DEFAULT_ENCODING
                        : String.valueOf(
                        configuration.get("encoding")
                );

        boolean overwrite =
                configuration.get("overwrite") == null
                        || Boolean.TRUE.equals(
                        configuration.get("overwrite")
                );

        boolean createDirectories =
                configuration.get("createDirectories") == null
                        || Boolean.TRUE.equals(
                        configuration.get("createDirectories")
                );

        try {
            Path root =
                    Paths.get(workspaceRoot)
                            .toAbsolutePath()
                            .normalize();

            Path file =
                    root.resolve(relativePath)
                            .normalize();

            if (!file.startsWith(root)) {
                throw new PluginConfigurationException(
                        "FILE_WRITE path escapes the workspace root"
                );
            }

            if (Files.exists(file)
                    && !Files.isRegularFile(file)) {

                throw new IllegalStateException(
                        "Path is not a regular file: " + relativePath
                );
            }

            if (createDirectories) {
                Path parent =
                        file.getParent();

                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } else if (file.getParent() != null
                    && !Files.exists(file.getParent())) {

                throw new IllegalStateException(
                        "Parent directory does not exist: "
                                + file.getParent()
                );
            }

            Charset charset =
                    Charset.forName(encoding);

            if (overwrite) {
                Files.writeString(
                        file,
                        content,
                        charset,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            } else {
                Files.writeString(
                        file,
                        content,
                        charset,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
            }

            return PluginResult.builder()
                    .success(true)
                    .output(
                            "File written successfully"
                    )
                    .metadata(
                            Map.of(
                                    "operation",
                                    "WRITE",
                                    "path",
                                    relativePath,
                                    "sizeBytes",
                                    Files.size(file),
                                    "encoding",
                                    charset.name(),
                                    "overwrite",
                                    overwrite
                            )
                    )
                    .build();

        } catch (Exception ex) {

            return PluginResult.builder()
                    .success(false)
                    .output(
                            ex.getMessage() == null
                                    ? ex.getClass().getSimpleName()
                                    : ex.getMessage()
                    )
                    .metadata(
                            Map.of(
                                    "operation",
                                    "WRITE",
                                    "path",
                                    relativePath
                            )
                    )
                    .build();
        }
    }

    private void validateRelativePath(
            String path
    ) {

        Path parsed =
                Paths.get(path);

        if (parsed.isAbsolute()
                || path.startsWith("/")
                || path.startsWith("\\")
                || path.contains(":")) {

            throw new PluginConfigurationException(
                    "FILE_WRITE 'path' must be relative to the workspace root"
            );
        }

        for (Path segment : parsed) {
            if ("..".equals(segment.toString())) {
                throw new PluginConfigurationException(
                        "FILE_WRITE 'path' cannot contain '..' traversal segments"
                );
            }
        }
    }

    private void validateBoolean(
            Map<String, Object> configuration,
            String key
    ) {

        Object value =
                configuration.get(key);

        if (value != null
                && !(value instanceof Boolean)) {

            throw new PluginConfigurationException(
                    "FILE_WRITE '" + key + "' must be a boolean"
            );
        }
    }

    private void validateEncoding(
            Map<String, Object> configuration
    ) {

        Object encoding =
                configuration.get("encoding");

        if (encoding == null) {
            return;
        }

        if (!(encoding instanceof String value)
                || value.isBlank()) {

            throw new PluginConfigurationException(
                    "FILE_WRITE 'encoding' must be a non-empty string"
            );
        }

        try {
            Charset.forName(value);
        } catch (Exception ex) {
            throw new PluginConfigurationException(
                    "FILE_WRITE has an unsupported encoding: " + value,
                    ex
            );
        }
    }

    private void requireNonBlank(
            Map<String, Object> configuration,
            String key,
            String message
    ) {

        Object value =
                configuration.get(key);

        if (!(value instanceof String string)
                || string.isBlank()) {

            throw new PluginConfigurationException(message);
        }
    }
}