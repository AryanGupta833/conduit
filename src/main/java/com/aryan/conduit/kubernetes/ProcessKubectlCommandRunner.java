package com.aryan.conduit.kubernetes;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
class ProcessKubectlCommandRunner implements KubectlCommandRunner {
    @Override
    public CommandResult run(List<String> command, String stdin, Duration timeout) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            Process running = process;
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outReader = reader(running.getInputStream(), stdout);
            Thread errReader = reader(running.getErrorStream(), stderr);
            outReader.start();
            errReader.start();
            try (OutputStream input = process.getOutputStream()) {
                if (stdin != null) input.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                outReader.join(1000);
                errReader.join(1000);
                return new CommandResult(-1, stdout.toString(), stderr.toString(), true);
            }
            outReader.join();
            errReader.join();
            return new CommandResult(process.exitValue(), stdout.toString(), stderr.toString(), false);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            throw new IllegalStateException("kubectl command failed to start or complete", e);
        }
    }

    private Thread reader(InputStream stream, StringBuilder destination) {
        return new Thread(() -> {
            try (stream) {
                destination.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                destination.append(e.getMessage());
            }
        });
    }
}
