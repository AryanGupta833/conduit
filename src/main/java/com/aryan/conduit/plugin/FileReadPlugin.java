package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FileReadPlugin implements WorkflowPlugin {

    private static final String DEFAULT_ENCODING = "UTF-8";

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata(
                "FILE_READ",
                "File Read",
                "Read a text file from a controlled workflow workspace.",
                "1.0.0",
                Map.of(
                        "type", "object",
                        "required", List.of(
                                "workspaceRoot",
                                "path"
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
                                "encoding", Map.of(
                                        "type", "string",
                                        "default", DEFAULT_ENCODING,
                                        "description",
                                        "Text file character encoding"
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
                    "FILE_READ configuration is required"
            );
        }

        requireNonBlank(
                configuration,
                "workspaceRoot",
                "FILE_READ requires a non-empty 'workspaceRoot'"
        );

        requireNonBlank(
                configuration,
                "path",
                "FILE_READ requires a non-empty 'path'"
        );

        validateRelativePath(
                String.valueOf(configuration.get("path"))
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

        String encoding =
                configuration.get("encoding") == null
                        ? DEFAULT_ENCODING
                        : String.valueOf(
                        configuration.get("encoding")
                );

        try {
            Path file =
                    resolveWorkspacePath(
                            workspaceRoot,
                            relativePath
                    );

            Charset charset =
                    Charset.forName(encoding);

            String content =
                    Files.readString(
                            file,
                            charset
                    );

            return PluginResult.builder()
                    .success(true)
                    .output(content)
                    .metadata(
                            Map.of(
                                    "operation",
                                    "READ",
                                    "path",
                                    relativePath,
                                    "sizeBytes",
                                    Files.size(file),
                                    "encoding",
                                    charset.name()
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
                                    "READ",
                                    "path",
                                    relativePath
                            )
                    )
                    .build();
        }
    }

    private Path resolveWorkspacePath(
            String workspaceRoot,
            String relativePath
    ) throws IOException {

        Path root =
                Paths.get(workspaceRoot)
                        .toAbsolutePath()
                        .normalize();

        Path resolved =
                root.resolve(relativePath)
                        .normalize();

        if (!resolved.startsWith(root)) {
            throw new PluginConfigurationException(
                    "FILE_READ path escapes the workspace root"
            );
        }

        if (!Files.exists(resolved)) {
            throw new IOException(
                    "File does not exist: " + relativePath
            );
        }

        if (!Files.isRegularFile(resolved)) {
            throw new IOException(
                    "Path is not a regular file: " + relativePath
            );
        }

        return resolved;
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
                    "FILE_READ 'path' must be relative to the workspace root"
            );
        }

        for (Path segment : parsed) {
            if ("..".equals(segment.toString())) {
                throw new PluginConfigurationException(
                        "FILE_READ 'path' cannot contain '..' traversal segments"
                );
            }
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
                    "FILE_READ 'encoding' must be a non-empty string"
            );
        }

        try {
            Charset.forName(value);
        } catch (Exception ex) {
            throw new PluginConfigurationException(
                    "FILE_READ has an unsupported encoding: " + value,
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