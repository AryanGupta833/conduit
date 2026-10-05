package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.entity.WorkflowStatus;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class HumanWaitStatusPostgresTest {
    @Autowired WorkflowRepository workflows;
    @Autowired WorkflowVersionRepository versions;
    @Autowired WorkflowExecutionRepository executions;
    @Autowired TaskNodeRepository taskNodes;
    @Autowired TaskExecutionRepository taskExecutions;

    @Test
    void persistsWaitingHumanStatusesForWorkflowAndTaskExecutions() {
        Workflow workflow = workflows.saveAndFlush(Workflow.builder()
                .name("human-wait-status-" + UUID.randomUUID())
                .status(WorkflowStatus.ACTIVE).active(false).createdAt(LocalDateTime.now()).build());
        WorkflowVersion version = versions.saveAndFlush(WorkflowVersion.builder()
                .workflow(workflow).versionNumber(1).published(true).latest(true).createdAt(LocalDateTime.now()).build());
        WorkflowExecution execution = executions.saveAndFlush(WorkflowExecution.builder()
                .workflowVersion(version).versionNumber(1).status(WorkflowExecutionStatus.WAITING_FOR_HUMAN)
                .startedAt(LocalDateTime.now()).build());
        TaskNode node = taskNodes.saveAndFlush(TaskNode.builder()
                .workflowVersion(version).name("human").pluginType("HUMAN_TASK").configurationJson("{}")
                .timeoutSeconds(30).maxRetries(0).build());
        TaskExecution task = taskExecutions.saveAndFlush(TaskExecution.builder()
                .workflowExecution(execution).taskNode(node).status(TaskExecutionStatus.WAITING_FOR_HUMAN)
                .retryCount(0).idempotencyKey("human-wait-" + UUID.randomUUID()).build());

        assertEquals(WorkflowExecutionStatus.WAITING_FOR_HUMAN,
                executions.findById(execution.getId()).orElseThrow().getStatus());
        assertEquals(TaskExecutionStatus.WAITING_FOR_HUMAN,
                taskExecutions.findById(task.getId()).orElseThrow().getStatus());

        taskExecutions.delete(task);
        taskNodes.delete(node);
        executions.delete(execution);
        versions.delete(version);
        workflows.delete(workflow);
    }
}
