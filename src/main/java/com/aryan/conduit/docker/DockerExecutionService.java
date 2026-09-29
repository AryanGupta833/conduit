package com.aryan.conduit.docker;

import com.aryan.conduit.execution.TaskExecutionBackend;
import com.aryan.conduit.execution.TaskExecutionResult;
import com.aryan.conduit.observability.ConduitMetrics;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DockerExecutionService implements TaskExecutionBackend {
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
            TaskExecutionResult result = executeInternal(image, command, timeout);
            status = result.timedOut() ? "timeout" : result.exitCode() == 0 ? "success" : "failed";
            return result;
        } finally {
            metrics.backendFinished("docker", status, System.nanoTime() - started);
        }
    }

    private TaskExecutionResult executeInternal(String image, List<String> command, Duration timeout) {
        String containerName =
                "conduit-task-" + UUID.randomUUID();

        List<String> dockerCommand = new ArrayList<>();

        dockerCommand.add("docker");
        dockerCommand.add("run");
        dockerCommand.add("--name");
        dockerCommand.add(containerName);
        dockerCommand.add("--rm");

        dockerCommand.add("--cpus");
        dockerCommand.add("1.0");

        dockerCommand.add("--memory");
        dockerCommand.add("256m");

        dockerCommand.add(image);
        dockerCommand.addAll(command);

        Process process = null;

        try {
            process = new ProcessBuilder(dockerCommand)
                    .start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

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

            boolean completed =
                    process.waitFor(
                            timeout.toMillis(),
                            TimeUnit.MILLISECONDS
                    );

            if (!completed) {

                // Docker process itself may still be running.
                process.destroyForcibly();

                cleanupContainer(containerName);

                stdoutThread.join(1000);
                stderrThread.join(1000);

                return new TaskExecutionResult(
                        containerName,
                        stdout.toString(),
                        stderr.toString(),
                        -1,
                        true
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
                    false
            );

        } catch (Exception e) {

            if (process != null) {
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

    private void readStream(
            InputStream inputStream,
            StringBuilder output
    ) {
        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(inputStream)
                        )
        ) {

            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line)
                        .append("\n");
            }

        } catch (Exception e) {
            output.append(
                    "Failed to read process output: "
            ).append(e.getMessage());
        }
    }
}
