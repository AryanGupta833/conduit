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
            BoundedCapture stdout = new BoundedCapture();
            BoundedCapture stderr = new BoundedCapture();
            Thread outReader = reader(running.getInputStream(), stdout);
            Thread errReader = reader(running.getErrorStream(), stderr);
            outReader.start();
            errReader.start();
            try (OutputStream input = process.getOutputStream()) {
                if (stdin != null) input.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                outReader.join(1000);
                errReader.join(1000);
                return new CommandResult(-1, stdout.toString(), stderr.toString(), true, stdout.truncated || stderr.truncated);
            }
            outReader.join();
            errReader.join();
            return new CommandResult(process.exitValue(), stdout.toString(), stderr.toString(), false, stdout.truncated || stderr.truncated);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            throw new IllegalStateException("kubectl command was interrupted", e);
        } catch (Exception e) {
            if (process != null) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            throw new IllegalStateException("kubectl command failed to start or complete", e);
        }
    }

    private Thread reader(InputStream stream, BoundedCapture destination) {
        Thread thread = new Thread(() -> {
            try (stream) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) destination.append(buffer, count);
            } catch (Exception e) {
                byte[] failure = ("[stream read failed: " + e.getClass().getSimpleName() + "]").getBytes(StandardCharsets.UTF_8);
                destination.append(failure, failure.length);
            }
        }, "kubectl-output-reader");
        thread.setDaemon(true);
        return thread;
    }

    private static final class BoundedCapture {
        private static final int LIMIT = 64 * 1024;
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private volatile boolean truncated;
        synchronized void append(byte[] value, int length) {
            int room = LIMIT - bytes.size();
            if (room > 0) bytes.write(value, 0, Math.min(room, length));
            if (length > room) truncated = true;
        }
        @Override public synchronized String toString() { return bytes.toString(StandardCharsets.UTF_8) + (truncated ? "\n[output truncated]" : ""); }
    }
}
