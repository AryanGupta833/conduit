package com.aryan.conduit.execution.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import com.aryan.conduit.plugin.sdk.PluginWorkspace;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.locks.ReentrantLock;

/** A filesystem namespace owned by one workflow execution. */
public final class ExecutionWorkspace implements PluginWorkspace {
    private final Path root;
    private final long maximumBytes;
    private final ReentrantLock quotaLock;

    ExecutionWorkspace(Path root, long maximumBytes, ReentrantLock quotaLock) {
        this.root = root.toAbsolutePath().normalize();
        this.maximumBytes = maximumBytes;
        this.quotaLock = quotaLock;
    }

    public Path root() { return root; }
    public Path taskDirectory(long taskId) throws IOException { return directory("tasks", Long.toString(taskId)); }
    public Path artifacts() throws IOException { return directory("artifacts"); }
    public Path working() throws IOException { return directory("working"); }
    public Path logs() throws IOException { return directory("logs"); }
    public long maximumBytes() { return maximumBytes; }

    public long usedBytes() throws IOException {
        try (var paths = Files.walk(root)) {
            long total = 0;
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(path)) {
                    // Unknown sizes must fail quota checks closed; treating an
                    // unreadable file as zero bytes could bypass the workspace cap.
                    total = Math.addExact(total, Files.size(path));
                }
            }
            return total;
        }
    }

    /** Stream to a same-directory temporary file and publish atomically after quota checks. */
    public long writeAtomic(String logicalPath, InputStream input, boolean overwrite, boolean createDirectories) throws IOException {
        quotaLock.lock();
        try (var lockChannel = java.nio.channels.FileChannel.open(root.resolve(".quota.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var processLock = lockChannel.lock()) {
        Path destination = resolve(logicalPath);
        Path parent = destination.getParent();
        if (parent == null) throw new IOException("Workspace file must have a parent directory");
        if (createDirectories) Files.createDirectories(parent);
        else if (!Files.isDirectory(parent)) throw new IOException("Workspace parent directory does not exist");
        resolve(logicalPath); // Recheck after parent creation to reject newly introduced symlinks.
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !overwrite)
            throw new IOException("Workspace file already exists");
        if (Files.isSymbolicLink(destination)) throw new IOException("Workspace symlinks are not allowed");
        long previous = Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) ? Files.size(destination) : 0;
        long baseUsage = usedBytes() - previous;
        Path temporary = Files.createTempFile(parent, ".conduit-", ".partial");
        long written = 0;
        try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                if (baseUsage + written + count > maximumBytes)
                    throw new WorkspaceLimitException("Execution workspace size limit exceeded");
                output.write(buffer, 0, count);
                written += count;
            }
        } catch (Exception e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        try {
            if (overwrite) Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            if (overwrite) Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return written;
        } finally {
            quotaLock.unlock();
        }
    }

    /** Resolve a logical relative path without following a symlink outside the workspace. */
    public Path resolve(String logicalPath) throws IOException {
        boolean logicalRoot = "workspace:/".equals(logicalPath);
        String normalizedLogical = logicalPath != null && logicalPath.startsWith("workspace:/")
                ? logicalPath.substring("workspace:/".length()) : logicalPath;
        if (normalizedLogical == null || (normalizedLogical.isBlank() && !logicalRoot)) throw new IllegalArgumentException("Workspace path is required");
        if (logicalRoot) return root;
        Path relative = Path.of(normalizedLogical);
        if (relative.isAbsolute()) throw new IllegalArgumentException("Workspace path must be relative");
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Workspace path escapes execution workspace");
        Path existing = root;
        for (Path part : root.relativize(resolved)) {
            existing = existing.resolve(part);
            if (Files.isSymbolicLink(existing)) throw new IllegalArgumentException("Symbolic links are not permitted in the execution workspace");
        }
        return resolved;
    }

    @Override public java.io.InputStream openRead(String logicalPath) throws IOException {
        Path path = resolve(logicalPath);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Workspace file is not a regular file");
        return Files.newInputStream(path);
    }

    private Path directory(String first, String... more) throws IOException {
        Path path = root.resolve(first);
        for (String item : more) path = path.resolve(item);
        path = path.normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Workspace path escapes execution workspace");
        Files.createDirectories(path);
        if (!path.toRealPath().startsWith(root.toRealPath())) throw new IllegalArgumentException("Workspace directory escapes through a symbolic link");
        return path;
    }

    static String safeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Execution ID contains unsupported characters");
        return id;
    }
}
