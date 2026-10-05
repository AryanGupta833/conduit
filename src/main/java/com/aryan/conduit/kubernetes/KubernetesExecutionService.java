package com.aryan.conduit.kubernetes;

import com.aryan.conduit.execution.TaskExecutionBackend;
import com.aryan.conduit.execution.TaskExecutionResult;
import com.aryan.conduit.observability.ConduitMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service("kubernetesExecutionService")
public class KubernetesExecutionService implements TaskExecutionBackend {
    private static final Logger log = LoggerFactory.getLogger(KubernetesExecutionService.class);
    private final KubectlCommandRunner runner;
    private final ObjectMapper mapper;
    private final String namespace;
    private final ConduitMetrics metrics;

    public KubernetesExecutionService(KubectlCommandRunner runner, ObjectMapper mapper,
                                      @Value("${conduit.kubernetes.namespace:default}") String namespace,
                                      ConduitMetrics metrics) {
        this.runner = runner;
        this.mapper = mapper;
        if (namespace == null || !namespace.matches("[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?"))
            throw new IllegalArgumentException("Kubernetes namespace must be a DNS label");
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
            String manifest = mapper.writeValueAsString(jobManifest(jobName, image, command, timeout));
            KubectlCommandRunner.CommandResult create = run(List.of("kubectl", "apply", "-f", "-"), manifest, timeout);
            if (!ok(create)) throw new IllegalStateException("Unable to create Kubernetes Job: " + create.stderr());

            long pollDelayMs = 250;
            while (System.nanoTime() < deadline) {
                KubectlCommandRunner.CommandResult status = run(
                        List.of("kubectl", "get", "job", jobName, "-n", namespace, "-o", "json"),
                        null, Duration.ofSeconds(Math.max(1, Math.min(10, remainingSeconds(deadline)))));
                if (status.exitCode() != 0) throw new IllegalStateException("Unable to read Kubernetes Job status: " + status.stderr());
                JsonNode job = mapper.readTree(status.stdout());
                JsonNode jobStatus = job.path("status");
                if (jobStatus.path("succeeded").asInt() > 0) {
                    String logs = logs(jobName, deadline);
                    return new TaskExecutionResult(jobName, logs, "", 0, false, logs.contains("[output truncated]"));
                }
                if (jobStatus.path("failed").asInt() > 0) {
                    String logs = logs(jobName, deadline);
                    String reason = job.path("status").path("conditions").toString();
                    return new TaskExecutionResult(jobName, logs, reason, 1, false, logs.contains("[output truncated]"));
                }
                Thread.sleep(Math.min(pollDelayMs, Math.max(1, remainingMillis(deadline))));
                pollDelayMs = Math.min(2_000, pollDelayMs * 2);
            }
            String partialLogs = logs(jobName, System.nanoTime() + Duration.ofSeconds(5).toNanos());
            return new TaskExecutionResult(jobName, partialLogs, "", -1, true, partialLogs.contains("[output truncated]"));
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
        return jobManifest(name, image, command, Duration.ofMinutes(30));
    }

    Map<String, Object> jobManifest(String name, String image, List<String> command, Duration timeout) {
        Map<String, Object> container = new java.util.LinkedHashMap<>();
        container.put("name", "task");
        container.put("image", image);
        container.put("command", command);
        container.put("securityContext", Map.of("allowPrivilegeEscalation", false, "privileged", false,
                "readOnlyRootFilesystem", true, "runAsNonRoot", true, "runAsUser", 10001,
                "capabilities", Map.of("drop", List.of("ALL"))));
        container.put("resources", Map.of("requests", Map.of("cpu", "100m", "memory", "64Mi"),
                "limits", Map.of("cpu", "1", "memory", "256Mi")));
        container.put("volumeMounts", List.of(Map.of("name", "tmp", "mountPath", "/tmp")));
        Map<String, Object> podSpec = new java.util.LinkedHashMap<>();
        podSpec.put("restartPolicy", "Never");
        podSpec.put("automountServiceAccountToken", false);
        podSpec.put("securityContext", Map.of("runAsNonRoot", true, "runAsUser", 10001,
                "seccompProfile", Map.of("type", "RuntimeDefault")));
        podSpec.put("containers", List.of(container));
        podSpec.put("volumes", List.of(Map.of("name", "tmp", "emptyDir", Map.of("sizeLimit", "64Mi"))));
        Map<String, Object> jobSpec = new java.util.LinkedHashMap<>();
        jobSpec.put("backoffLimit", 0);
        jobSpec.put("ttlSecondsAfterFinished", 60);
        jobSpec.put("activeDeadlineSeconds", Math.max(1, timeout.toSeconds()));
        jobSpec.put("template", Map.of("metadata", Map.of("labels", Map.of("app", "conduit-task")), "spec", podSpec));
        return Map.of(
                "apiVersion", "batch/v1", "kind", "Job",
                "metadata", Map.of("name", name, "namespace", namespace, "labels", Map.of("app", "conduit-task", "managed-by", "conduit")),
                "spec", jobSpec);
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
            KubectlCommandRunner.CommandResult result = runner.run(List.of("kubectl", "delete", "job", jobName, "-n", namespace, "--ignore-not-found=true", "--wait=false"), null, Duration.ofSeconds(10));
            if (result.timedOut() || result.exitCode() != 0) {
                log.warn("Kubernetes Job cleanup did not complete for {}: {}", jobName, result.stderr());
            }
        } catch (Exception e) {
            log.warn("Unable to clean up Kubernetes Job {}", jobName, e);
        }
    }
}
