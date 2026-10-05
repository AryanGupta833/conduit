package com.aryan.conduit.execution.workspace;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates execution-scoped workspaces under a single configured host root. */
@Service

public class ExecutionWorkspaceService {
    private static final Logger log = LoggerFactory.getLogger(ExecutionWorkspaceService.class);
    private final Path root;
    private final long maximumBytes;
    private final ConcurrentMap<String, ReentrantLock> quotaLocks = new ConcurrentHashMap<>();
    private final Duration successRetention;
    private final Duration failureRetention;
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    public ExecutionWorkspaceService(
            @Value("${conduit.workspace.root:./conduit-workspaces}") String root,
            @Value("${conduit.workspace.max-bytes:1073741824}") long maximumBytes,
            @Value("${conduit.workspace.success-retention:PT24H}") Duration successRetention,
            @Value("${conduit.workspace.failure-retention:P7D}") Duration failureRetention) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        if (maximumBytes <= 0) throw new IllegalArgumentException("conduit.workspace.max-bytes must be positive");
        this.maximumBytes = maximumBytes;
        if (successRetention.isNegative() || failureRetention.isNegative())
            throw new IllegalArgumentException("Workspace retention durations must not be negative");
        this.successRetention = successRetention;
        this.failureRetention = failureRetention;
    }

    public ExecutionWorkspaceService(String root, long maximumBytes) {
        this(root, maximumBytes, Duration.ofHours(24), Duration.ofDays(7));
    }

    @Autowired(required = false)
    void setExecutionRepository(WorkflowExecutionRepository executionRepository) { this.executionRepository = executionRepository; }

    @PostConstruct
    void initialize() {
        try {
            Files.createDirectories(root);
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("Workspace root must be a directory");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize execution workspace root", e);
        }
    }

    public ExecutionWorkspace forExecution(String executionId) throws IOException {
        String safe = ExecutionWorkspace.safeId(executionId);
        Path path = root.resolve(safe).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid execution workspace ID");
        Files.createDirectories(path);
        if (!path.toRealPath().startsWith(root.toRealPath())) throw new IllegalArgumentException("Execution workspace escapes configured root");
        return new ExecutionWorkspace(path, maximumBytes, quotaLocks.computeIfAbsent(safe, ignored -> new ReentrantLock()));
    }

    public Path root() { return root; }

    public void markFinished(String executionId, boolean success) {
        try {
            Path directory = root.resolve(ExecutionWorkspace.safeId(executionId)).normalize();
            if (!directory.startsWith(root) || !Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            Path status = directory.resolve(".conduit-status");
            String expected = success ? "SUCCESS" : "FAILED";
            if (Files.isRegularFile(status, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(status) && Files.readString(status).equals(expected)) return;
            Path temporary = Files.createTempFile(directory, ".status-", ".partial");
            Files.writeString(temporary, expected);
            try { Files.move(temporary, status, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, status, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { log.warn("Unable to record workspace completion for execution {}", executionId, e); }
    }

    /** Reconciles terminal workspace state from PostgreSQL and removes expired workspaces. */
    @Scheduled(fixedDelayString = "${conduit.workspace.cleanup-interval-ms:3600000}")
    public synchronized void cleanupExpired() {
        if (executionRepository == null) return;
        try (var directories = Files.list(root)) {
            for (Path directory : directories.filter(path -> Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                String id = directory.getFileName().toString();
                if (!id.matches("[0-9]{1,19}")) continue;
                var execution = executionRepository.findById(Long.parseLong(id));
                if (execution.isEmpty()) continue;
                WorkflowExecutionStatus status = execution.get().getStatus();
                if (status == WorkflowExecutionStatus.RUNNING || status == WorkflowExecutionStatus.PAUSED) continue;
                boolean success = status == WorkflowExecutionStatus.SUCCESS;
                markFinished(id, success);
                Path marker = directory.resolve(".conduit-status");
                Instant expiredAt = Files.getLastModifiedTime(marker).toInstant().plus(success ? successRetention : failureRetention);
                if (Instant.now().isBefore(expiredAt)) continue;
                ReentrantLock localLock = quotaLocks.computeIfAbsent(id, ignored -> new ReentrantLock());
                if (!localLock.tryLock()) continue;
                try {
                    Path locks = root.resolve(".cleanup-locks");
                    Files.createDirectories(locks);
                    try (FileChannel channel = FileChannel.open(locks.resolve(id + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                        FileLock lock;
                        try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { lock = null; }
                        if (lock == null) continue;
                        FileLock heldLock = lock;
                        try (heldLock; var paths = Files.walk(directory)) {
                            var orderedPaths = paths.sorted(Comparator.reverseOrder()).toList();
                            // Never delete through or remove links/reparse points left by plugins,
                            // Docker, or cloud-synced Windows directories.
                            boolean containsSpecialPath = false;
                            for (Path path : orderedPaths) {
                                BasicFileAttributes attributes = Files.readAttributes(
                                        path, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                                if (Files.isSymbolicLink(path) || attributes.isOther()) {
                                    containsSpecialPath = true;
                                    break;
                                }
                            }
                            if (containsSpecialPath) {
                                log.debug("Skipping expired workspace cleanup because it contains a symbolic link or reparse point: {}", id);
                                continue;
                            }
                            for (Path path : orderedPaths) Files.deleteIfExists(path);
                        }
                    }
                } finally {
                    localLock.unlock();
                    quotaLocks.remove(id, localLock);
                }
            }
        } catch (Exception e) { log.warn("Workspace cleanup pass failed; it will be retried: {}", e.getMessage()); }
    }
}
