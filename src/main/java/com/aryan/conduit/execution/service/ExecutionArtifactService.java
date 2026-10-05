package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.dto.ExecutionArtifactResponse;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.workspace.ExecutionWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ExecutionArtifactService {
    private static final int MAX_ITEMS = 1000;
    private static final Pattern TASK_DIRECTORY = Pattern.compile("(?:^|/)tasks/([0-9]{1,19})(?:/|$).*");
    private final ExecutionWorkspaceService workspaces;
    private final WorkflowExecutionRepository executions;

    public List<ExecutionArtifactResponse> list(Long executionId) {
        if (!executions.existsById(executionId)) throw new IllegalArgumentException("Execution not found: " + executionId);
        try {
            Path root = workspaces.forExecution(executionId.toString()).root().toRealPath();
            try (var stream = Files.walk(root, 8)) {
                return stream.filter(path -> !path.equals(root))
                        .filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                        .map(path -> toResponse(root, path))
                        .filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparing(ExecutionArtifactResponse::path))
                        .limit(MAX_ITEMS)
                        .toList();
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Unable to list managed execution artifacts", error);
        }
    }

    private ExecutionArtifactResponse toResponse(Path root, Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(root) || Files.isSymbolicLink(path)) return null;
            String relative = root.relativize(normalized).toString().replace('\\', '/');
            if (relative.startsWith(".") || relative.contains("/.")) return null;
            var taskMatch = TASK_DIRECTORY.matcher(relative);
            Long taskId = taskMatch.matches() ? Long.valueOf(taskMatch.group(1)) : null;
            String area = relative.startsWith("artifacts/") ? "artifacts"
                    : relative.startsWith("working/") ? "working"
                    : relative.startsWith("tasks/") ? "task"
                    : relative.startsWith("logs/") ? "logs" : "workspace";
            return new ExecutionArtifactResponse(relative, Files.size(path), Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant(), taskId, area);
        } catch (Exception ignored) {
            return null;
        }
    }
}
