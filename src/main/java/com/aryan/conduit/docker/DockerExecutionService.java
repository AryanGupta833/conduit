package com.aryan.conduit.docker;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DockerExecutionService {

    public DockerExecutionResult execute(
            String image,
            List<String> command,
            Duration timeout
    ) {
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

                return new DockerExecutionResult(
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

            // --rm should normally remove it automatically,
            // but cleanup makes lifecycle behavior explicit.
            cleanupContainer(containerName);

            return new DockerExecutionResult(
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
            Process cleanupProcess =
                    new ProcessBuilder(
                            "docker",
                            "rm",
                            "-f",
                            containerName
                    ).start();

            cleanupProcess.waitFor(
                    5,
                    TimeUnit.SECONDS
            );

        } catch (Exception ignored) {
            // Best-effort cleanup.
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