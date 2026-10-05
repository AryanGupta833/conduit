package com.aryan.conduit.docker;

import com.aryan.conduit.execution.TaskExecutionBackend;
import com.aryan.conduit.execution.TaskExecutionResult;
import com.aryan.conduit.execution.workspace.ExecutionWorkspace;
import com.aryan.conduit.observability.ConduitMetrics;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DockerExecutionService implements TaskExecutionBackend {
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;
    private static final Logger log = LoggerFactory.getLogger(DockerExecutionService.class);
    private final ConduitMetrics metrics;

    public DockerExecutionService(ConduitMetrics metrics) { this.metrics = metrics; }

    public TaskExecutionResult execute(
            String image,
            List<String> command,
            Duration timeout
    ) {
        long started = System.nanoTime();
        String status = "failed";
        try {
            TaskExecutionResult result = executeInternal(image, command, timeout, null, false);
            status = result.timedOut() ? "timeout" : result.exitCode() == 0 ? "success" : "failed";
            return result;
        } finally {
            metrics.backendFinished("docker", status, System.nanoTime() - started);
        }
    }

    @Override
    public TaskExecutionResult execute(String image, List<String> command, Duration timeout,
                                       ExecutionWorkspace workspace, boolean readOnlyWorkspace) {
        long started = System.nanoTime();
        String status = "failed";
        try {
            TaskExecutionResult result = executeInternal(image, command, timeout, workspace, readOnlyWorkspace);
            status = result.timedOut() ? "timeout" : result.exitCode() == 0 ? "success" : "failed";
            return result;
        } finally { metrics.backendFinished("docker", status, System.nanoTime() - started); }
    }

    @Override
    public TaskExecutionResult executeScript(String image, String script, Duration timeout,
                                             ExecutionWorkspace workspace, boolean readOnlyWorkspace) {
        return executeScript(image, script, timeout, workspace, readOnlyWorkspace, 1.0, 256, 256, false);
    }

    public TaskExecutionResult executeScript(String image, String script, Duration timeout,
                                             ExecutionWorkspace workspace, boolean readOnlyWorkspace,
                                             double cpus, long memoryMb, long pidsLimit, boolean networkDisabled) {
        long started = System.nanoTime();
        String status = "failed";
        try {
            // Keep workflow script text off the Windows docker.exe command line. The fixed
            // wrapper receives the script on stdin, writes it to the existing /tmp tmpfs,
            // then runs it with stdin detached as it was for `sh -c` executions.
            String wrapper = "cat > /tmp/.conduit-script && exec sh /tmp/.conduit-script </dev/null";
            TaskExecutionResult result = executeInternal(image, List.of("sh", "-c", wrapper), timeout,
                    workspace, readOnlyWorkspace, script, cpus, memoryMb, pidsLimit, networkDisabled);
            status = result.timedOut() ? "timeout" : result.exitCode() == 0 ? "success" : "failed";
            return result;
        } finally { metrics.backendFinished("docker", status, System.nanoTime() - started); }
    }

    private TaskExecutionResult executeInternal(String image, List<String> command, Duration timeout,
                                                ExecutionWorkspace workspace, boolean readOnlyWorkspace) {
        return executeInternal(image, command, timeout, workspace, readOnlyWorkspace, null);
    }

    private TaskExecutionResult executeInternal(String image, List<String> command, Duration timeout,
                                                ExecutionWorkspace workspace, boolean readOnlyWorkspace,
                                                String stdinScript) {
        return executeInternal(image, command, timeout, workspace, readOnlyWorkspace, stdinScript, 1.0, 256, 256, false);
    }

    private TaskExecutionResult executeInternal(String image, List<String> command, Duration timeout,
                                                ExecutionWorkspace workspace, boolean readOnlyWorkspace,
                                                String stdinScript, double cpus, long memoryMb, long pidsLimit,
                                                boolean networkDisabled) {
        String containerName =
                "conduit-task-" + UUID.randomUUID();

        List<String> dockerCommand;
        try {
            dockerCommand = buildDockerCommand(containerName, image, command, workspace, readOnlyWorkspace,
                    stdinScript != null, cpus, memoryMb, pidsLimit, networkDisabled);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Unable to resolve managed workspace mount", e);
        }

        Process process = null;

        try {
            process = new ProcessBuilder(dockerCommand)
                    .start();

            BoundedOutput stdout = new BoundedOutput();
            BoundedOutput stderr = new BoundedOutput();

            Process finalProcess = process;
            Thread stdoutThread = new Thread(
                    () -> readStream(
                            finalProcess.getInputStream(),
                            stdout
                    )
            );

            Process finalProcess1 = process;
            Thread stderrThread = new Thread(
                    () -> readStream(
                            finalProcess1.getErrorStream(),
                            stderr
                    )
            );

            stdoutThread.start();
            stderrThread.start();

            if (stdinScript != null) {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(stdinScript.getBytes(StandardCharsets.UTF_8));
                }
            }

            boolean completed =
                    process.waitFor(
                            timeout.toMillis(),
                            TimeUnit.MILLISECONDS
                    );

            if (!completed) {

                // Docker process itself may still be running.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();

                cleanupContainer(containerName);

                stdoutThread.join(1000);
                stderrThread.join(1000);

                return new TaskExecutionResult(
                        containerName,
                        stdout.toString(),
                        stderr.toString(),
                        -1,
                        true,
                        stdout.truncated() || stderr.truncated()
                );
            }

            int exitCode = process.exitValue();

            stdoutThread.join();
            stderrThread.join();

            return new TaskExecutionResult(
                    containerName,
                    stdout.toString(),
                    stderr.toString(),
                    exitCode,
                    false,
                    stdout.truncated() || stderr.truncated()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            cleanupContainer(containerName);
            throw new IllegalStateException("Docker execution was cancelled", e);
        } catch (Exception e) {

            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }

            cleanupContainer(containerName);

            throw new IllegalStateException(
                    "Docker execution failed",
                    e
            );
        }
    }

    private void cleanupContainer(String containerName) {

        try {
            Process cleanupProcess = new ProcessBuilder(
                            "docker",
                            "rm",
                            "-f",
                            containerName
                    )
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();

            if (!cleanupProcess.waitFor(5, TimeUnit.SECONDS)) {
                cleanupProcess.destroyForcibly();
                log.warn("Timed out while cleaning up Docker container {}", containerName);
            } else if (cleanupProcess.exitValue() != 0) {
                log.warn("Docker cleanup for container {} exited with code {}", containerName,
                        cleanupProcess.exitValue());
            }

        } catch (Exception e) {
            log.warn("Unable to clean up Docker container {}", containerName, e);
        }
    }

    private void readStream(InputStream inputStream, BoundedOutput output) {
        try (InputStream stream = inputStream) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) output.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            output.append("[output read failed: " + e.getClass().getSimpleName() + "]");
        }
    }

    List<String> buildDockerCommand(String containerName, String image, List<String> command,
                                    ExecutionWorkspace workspace, boolean readOnlyWorkspace) throws java.io.IOException {
        return buildDockerCommand(containerName, image, command, workspace, readOnlyWorkspace, false);
    }

    List<String> buildDockerCommand(String containerName, String image, List<String> command,
                                    ExecutionWorkspace workspace, boolean readOnlyWorkspace,
                                    boolean attachStdin) throws java.io.IOException {
        return buildDockerCommand(containerName, image, command, workspace, readOnlyWorkspace, attachStdin, 1.0, 256, 256, false);
    }

    List<String> buildDockerCommand(String containerName, String image, List<String> command,
                                    ExecutionWorkspace workspace, boolean readOnlyWorkspace,
                                    boolean attachStdin, double cpus, long memoryMb, long pidsLimit,
                                    boolean networkDisabled) throws java.io.IOException {
        List<String> dockerCommand = new ArrayList<>();
        dockerCommand.add("docker");
        dockerCommand.add("run");
        if (attachStdin) dockerCommand.add("-i");
        dockerCommand.add("--name");
        dockerCommand.add(containerName);
        dockerCommand.add("--rm");
        dockerCommand.add("--cpus");
        dockerCommand.add(String.valueOf(cpus));
        dockerCommand.add("--memory");
        dockerCommand.add(memoryMb + "m");
        dockerCommand.add("--pids-limit");
        dockerCommand.add(String.valueOf(pidsLimit));
        if (networkDisabled) { dockerCommand.add("--network"); dockerCommand.add("none"); }
        dockerCommand.add("--cap-drop");
        dockerCommand.add("ALL");
        dockerCommand.add("--security-opt");
        dockerCommand.add("no-new-privileges");
        dockerCommand.add("--read-only");
        dockerCommand.add("--tmpfs");
        dockerCommand.add("/tmp:rw,noexec,nosuid,size=64m");
        if (workspace != null) {
            dockerCommand.add("--mount");
            dockerCommand.add(ManagedWorkspaceMount.dockerMount(workspace, readOnlyWorkspace));
            dockerCommand.add("--workdir");
            dockerCommand.add(ManagedWorkspaceMount.CONTAINER_PATH);
        }
        dockerCommand.add(image);
        dockerCommand.addAll(command);
        return dockerCommand;
    }

    private static final class BoundedOutput {
        private final StringBuilder value = new StringBuilder();
        private boolean truncated;
        synchronized void append(String text) {
            int remaining = MAX_OUTPUT_CHARS - value.length();
            if (remaining > 0) value.append(text, 0, Math.min(remaining, text.length()));
            if (text.length() > remaining) truncated = true;
        }
        synchronized boolean truncated() { return truncated; }
        @Override public synchronized String toString() { return value + (truncated ? "\n[output truncated]" : ""); }
    }
}
