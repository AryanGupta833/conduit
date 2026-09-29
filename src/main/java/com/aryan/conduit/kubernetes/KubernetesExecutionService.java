package com.aryan.conduit.kubernetes;

import com.aryan.conduit.execution.TaskExecutionBackend;
import com.aryan.conduit.execution.TaskExecutionResult;
import com.aryan.conduit.observability.ConduitMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service("kubernetesExecutionService")
public class KubernetesExecutionService implements TaskExecutionBackend {
    private final KubectlCommandRunner runner;
    private final ObjectMapper mapper;
    private final String namespace;
    private final ConduitMetrics metrics;

    public KubernetesExecutionService(KubectlCommandRunner runner, ObjectMapper mapper,
                                      @Value("${conduit.kubernetes.namespace:default}") String namespace,
                                      ConduitMetrics metrics) {
        this.runner = runner;
        this.mapper = mapper;
        this.namespace = namespace;
        this.metrics = metrics;
    }

    @Override
    public TaskExecutionResult execute(String image, List<String> command, Duration timeout) {
        long started = System.nanoTime();
        String status = "failed";
        try {
            TaskExecutionResult result = executeInternal(image, command, timeout);
            status = result.timedOut() ? "timeout" : result.exitCode() == 0 ? "success" : "failed";
            return result;
        } finally {
            metrics.backendFinished("kubernetes", status, System.nanoTime() - started);
        }
    }

    private TaskExecutionResult executeInternal(String image, List<String> command, Duration timeout) {
        String jobName = "conduit-task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            String manifest = mapper.writeValueAsString(jobManifest(jobName, image, command));
            KubectlCommandRunner.CommandResult create = run(List.of("kubectl", "apply", "-f", "-"), manifest, timeout);
            if (!ok(create)) throw new IllegalStateException("Unable to create Kubernetes Job: " + create.stderr());

            while (System.nanoTime() < deadline) {
                KubectlCommandRunner.CommandResult status = run(
                        List.of("kubectl", "get", "job", jobName, "-n", namespace, "-o", "json"),
                        null, Duration.ofSeconds(Math.max(1, Math.min(10, remainingSeconds(deadline)))));
                if (status.exitCode() != 0) throw new IllegalStateException("Unable to read Kubernetes Job status: " + status.stderr());
                JsonNode job = mapper.readTree(status.stdout());
                JsonNode jobStatus = job.path("status");
                if (jobStatus.path("succeeded").asInt() > 0) {
                    String logs = logs(jobName, deadline);
                    return new TaskExecutionResult(jobName, logs, "", 0, false);
                }
                if (jobStatus.path("failed").asInt() > 0) {
                    String logs = logs(jobName, deadline);
                    String reason = job.path("status").path("conditions").toString();
                    return new TaskExecutionResult(jobName, logs, reason, 1, false);
                }
                Thread.sleep(Math.min(500, Math.max(1, remainingMillis(deadline))));
            }
            return new TaskExecutionResult(jobName,
                    logs(jobName, System.nanoTime() + Duration.ofSeconds(5).toNanos()), "", -1, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Kubernetes Job " + jobName, e);
        } catch (Exception e) {
            throw new IllegalStateException("Kubernetes execution failed for Job " + jobName, e);
        } finally {
            cleanup(jobName);
        }
    }

    Map<String, Object> jobManifest(String name, String image, List<String> command) {
        return Map.of(
                "apiVersion", "batch/v1", "kind", "Job",
                "metadata", Map.of("name", name, "namespace", namespace, "labels", Map.of("app", "conduit-task")),
                "spec", Map.of("backoffLimit", 0, "ttlSecondsAfterFinished", 60,
                        "template", Map.of("spec", Map.of("restartPolicy", "Never",
                                "containers", List.of(Map.of("name", "task", "image", image, "command", command,
                                        "resources", Map.of("requests", Map.of("cpu", "100m", "memory", "64Mi"),
                                                "limits", Map.of("cpu", "1", "memory", "256Mi"))))))));
    }

    private String logs(String jobName, long deadline) {
        KubectlCommandRunner.CommandResult result = run(
                List.of("kubectl", "logs", "job/" + jobName, "-n", namespace), null,
                Duration.ofSeconds(Math.max(1, Math.min(5, remainingSeconds(deadline)))));
        return result.stdout();
    }

    private KubectlCommandRunner.CommandResult run(List<String> command, String stdin, Duration timeout) {
        KubectlCommandRunner.CommandResult result = runner.run(command, stdin, timeout);
        if (result.timedOut()) throw new IllegalStateException("kubectl command timed out: " + String.join(" ", command));
        return result;
    }

    private boolean ok(KubectlCommandRunner.CommandResult result) { return result.exitCode() == 0; }
    private long remainingMillis(long deadline) { return Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis()); }
    private long remainingSeconds(long deadline) { return Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toSeconds()); }
    private void cleanup(String jobName) {
        try {
            runner.run(List.of("kubectl", "delete", "job", jobName, "-n", namespace, "--ignore-not-found=true", "--wait=false"), null, Duration.ofSeconds(10));
        } catch (Exception ignored) { }
    }
}
