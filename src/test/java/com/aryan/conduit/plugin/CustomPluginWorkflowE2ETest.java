package com.aryan.conduit.plugin;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.ExecutionLogRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.execution.queue.TaskMessage;
import com.aryan.conduit.execution.queue.TaskQueueService;
import com.aryan.conduit.execution.service.ExecutionContextService;
import com.aryan.conduit.execution.service.ExecutionCreationService;
import com.aryan.conduit.execution.service.ExecutionService;
import com.aryan.conduit.execution.service.TaskOutputService;
import com.aryan.conduit.observability.ConduitMetrics;
import com.aryan.conduit.plugin.external.TestCustomPlugin;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = CustomPluginWorkflowE2ETest.PluginJarInitializer.class)
class CustomPluginWorkflowE2ETest {
    private static final Path PLUGIN_DIRECTORY = Path.of("target", "custom-plugin-e2e", "plugins").toAbsolutePath();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkflowVersionRepository workflowVersionRepository;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired ExecutionCreationService executionCreationService;
    @Autowired ExecutionContextService executionContextService;
    @Autowired TaskQueueService taskQueueService;
    @Autowired WorkflowExecutionRepository workflowExecutionRepository;
    @Autowired TaskExecutionRepository taskExecutionRepository;
    @Autowired ExecutionLogRepository executionLogRepository;
    @Autowired TaskOutputService taskOutputService;
    @Autowired PluginManager pluginManager;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void externalPluginJarExecutesThroughWorkflowPipelineWithRetryFailureAndMetrics() throws Exception {
        assertTrue(pluginManager.getRegisteredPlugins().containsAll(
                java.util.List.of("HTTP", "SHELL", "LOG", "SLEEP", "FAIL", "CUSTOM_EXAMPLE")));
        JsonNode pluginListing = objectMapper.readTree(mockMvc.perform(get("/api/plugins"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode customMetadata = objectMapper.createArrayNode();
        for (JsonNode item : pluginListing) if (item.path("type").asText().equals("CUSTOM_EXAMPLE")) customMetadata = item;
        assertEquals("Test Custom Plugin", customMetadata.path("displayName").asText());
        assertEquals("test-1.0", customMetadata.path("version").asText());

        RunResult success = runWorkflow("custom-success", Map.of("message", "Custom plugin executed successfully",
                "recipientVariable", "username"), 0);
        report(success);
        assertEquals(TaskExecutionStatus.SUCCESS, success.task().getStatus(),
                () -> "Task logs: " + executionLogRepository.findByTaskExecution_Id(success.taskExecutionId()).stream()
                        .map(log -> log.getMessage()).toList());
        assertEquals(WorkflowExecutionStatus.SUCCESS, success.workflow().getStatus());
        assertEquals("Custom plugin executed successfully for Aryan", success.output().get("output"));
        assertEquals("CUSTOM_EXAMPLE", success.task().getTaskNode().getPluginType());
        assertEquals(1.0, meterRegistry.get("conduit.task.executions")
                .tag("plugin_type", "CUSTOM_EXAMPLE").tag("status", "SUCCESS").counter().count());

        RunResult retried = runWorkflow("custom-retry", Map.of("message", "Recovered after retry",
                "recipientVariable", "username", "failuresBeforeSuccess", 1,
                "scenario", "retry-" + UUID.randomUUID()), 1);
        report(retried);
        assertEquals(TaskExecutionStatus.SUCCESS, retried.task().getStatus(),
                () -> "Task logs: " + executionLogRepository.findByTaskExecution_Id(retried.taskExecutionId()).stream()
                        .map(log -> log.getMessage()).toList());
        assertEquals(1, retried.task().getRetryCount());
        assertEquals(WorkflowExecutionStatus.SUCCESS, retried.workflow().getStatus());
        assertEquals("Recovered after retry for Aryan", retried.output().get("output"));

        RunResult exhausted = runWorkflow("custom-exhausted", Map.of("message", "Will not complete",
                "recipientVariable", "username", "failuresBeforeSuccess", 10,
                "scenario", "exhausted-" + UUID.randomUUID()), 1);
        report(exhausted);
        assertEquals(TaskExecutionStatus.FAILED, exhausted.task().getStatus());
        assertEquals(1, exhausted.task().getRetryCount());
        assertEquals(WorkflowExecutionStatus.FAILED, exhausted.workflow().getStatus());

        RunResult invalid = runWorkflow("custom-invalid", Map.of("message", " "), 0);
        report(invalid);
        assertEquals(TaskExecutionStatus.FAILED, invalid.task().getStatus());
        assertEquals(WorkflowExecutionStatus.FAILED, invalid.workflow().getStatus());
        assertTrue(executionLogRepository.findByTaskExecution_Id(invalid.taskExecutionId()).stream()
                .anyMatch(log -> log.getMessage().contains("Invalid configuration for plugin 'CUSTOM_EXAMPLE'")));
    }

    private RunResult runWorkflow(String name, Map<String, Object> configuration, int maxRetries) throws Exception {
        MvcResult workflowResponse = mockMvc.perform(post("/api/workflows")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(Map.of("name", name))))
                .andExpect(status().isOk()).andReturn();
        long workflowId = objectMapper.readTree(workflowResponse.getResponse().getContentAsString()).path("id").asLong();
        WorkflowVersion version = workflowVersionRepository.findByWorkflow_IdAndLatestTrue(workflowId).orElseThrow();

        Map<String, Object> taskRequest = Map.of(
                "workflowId", workflowId, "name", "customTask", "timeoutSeconds", 20,
                "maxRetries", maxRetries, "pluginType", "CUSTOM_EXAMPLE",
                "configurationJson", objectMapper.writeValueAsString(configuration), "xPosition", 0, "yPosition", 0);
        MvcResult taskResponse = mockMvc.perform(post("/api/tasks").contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(taskRequest)))
                .andExpect(status().isOk()).andReturn();
        long taskNodeId = Long.parseLong(taskResponse.getResponse().getContentAsString().replace("\"", ""));

        ExecutionContext context = executionCreationService.createExecutionForVersion(version.getId());
        long workflowExecutionId = context.workflowExecution().getId();
        long taskExecutionId = context.taskExecutionMap().get(taskNodeId).getId();
        executionContextService.putVariable(workflowExecutionId, "username", "Aryan");
        taskQueueService.enqueue(new TaskMessage(workflowExecutionId, taskExecutionId, taskNodeId));

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            WorkflowExecution workflow = workflowExecutionRepository.findById(workflowExecutionId).orElseThrow();
            TaskExecution task = taskExecutionRepository.findById(taskExecutionId).orElseThrow();
            assertTrue(workflow.getStatus() == WorkflowExecutionStatus.SUCCESS || workflow.getStatus() == WorkflowExecutionStatus.FAILED,
                    "workflow did not reach a terminal state: " + workflow.getStatus());
            assertTrue(task.getStatus() == TaskExecutionStatus.SUCCESS || task.getStatus() == TaskExecutionStatus.FAILED,
                    "task did not reach a terminal state: " + task.getStatus());
        });
        TaskExecution task = taskExecutionRepository.findById(taskExecutionId).orElseThrow();
        WorkflowExecution workflow = workflowExecutionRepository.findById(workflowExecutionId).orElseThrow();
        Map<String, Object> output = task.getStatus() == TaskExecutionStatus.SUCCESS
                ? taskOutputService.getOutput(workflowExecutionId, taskNodeId) : Map.of();
        return new RunResult(workflowId, workflowExecutionId, taskExecutionId, taskNodeId, workflow, task, output);
    }

    private void report(RunResult run) {
        System.out.printf("CUSTOM_PLUGIN_E2E workflowId=%d workflowExecutionId=%d taskExecutionId=%d taskNodeId=%d pluginType=%s taskStatus=%s workflowStatus=%s output=%s%n",
                run.workflowId(), run.workflowExecutionId(), run.taskExecutionId(), run.taskNodeId(),
                "CUSTOM_EXAMPLE", run.task().getStatus(), run.workflow().getStatus(), run.output().get("output"));
    }

    private record RunResult(long workflowId, long workflowExecutionId, long taskExecutionId, long taskNodeId,
                             WorkflowExecution workflow, TaskExecution task, Map<String, Object> output) { }

    public static class PluginJarInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            try {
                Files.createDirectories(PLUGIN_DIRECTORY);
                Path jarPath = PLUGIN_DIRECTORY.resolve("test-custom-plugin.jar");
                String servicePath = "META-INF/services/com.aryan.conduit.plugin.sdk.WorkflowPlugin";
                String classPath = TestCustomPlugin.class.getName().replace('.', '/') + ".class";
                try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
                    jar.putNextEntry(new JarEntry(servicePath));
                    jar.write((TestCustomPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
                    jar.closeEntry();
                    try (InputStream bytecode = TestCustomPlugin.class.getClassLoader().getResourceAsStream(classPath)) {
                        if (bytecode == null) throw new IllegalStateException("Missing custom plugin test bytecode");
                        jar.putNextEntry(new JarEntry(classPath));
                        jar.write(bytecode.readAllBytes());
                        jar.closeEntry();
                    }
                }
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("customPluginJar",
                        Map.of("conduit.plugins.directory", PLUGIN_DIRECTORY.toString(),
                                "spring.data.redis.database", "15",
                                "conduit.execution.queue.stream", "conduit:test-custom-plugin:task-stream",
                                "conduit.execution.queue.group", "conduit-test-custom-plugin-workers")));
            } catch (Exception e) {
                throw new IllegalStateException("Unable to prepare test plugin JAR", e);
            }
        }
    }
}
