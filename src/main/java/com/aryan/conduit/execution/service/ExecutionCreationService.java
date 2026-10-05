package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.*;
import com.aryan.conduit.execution.repository.ExecutionContextRepository;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.observability.ConduitMetrics;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.aryan.conduit.workflow.service.WorkflowGraphService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ExecutionCreationService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final WorkflowGraphService workflowGraphService;
    private final ExecutionContextRepository executionContextRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final ConduitMetrics metrics;
    private final ExecutionEventService executionEvents;
    private final com.aryan.conduit.security.repository.ConduitUserRepository users;
    @org.springframework.beans.factory.annotation.Value("${conduit.execution.max-concurrent-per-account:25}")
    private int maxConcurrentPerAccount;
    private com.aryan.conduit.workflow.service.WorkflowEnvironmentProfileService environmentProfiles;
    private ExecutionContextService executionContexts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEnvironmentProfiles(com.aryan.conduit.workflow.service.WorkflowEnvironmentProfileService environmentProfiles) { this.environmentProfiles = environmentProfiles; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setExecutionContexts(ExecutionContextService executionContexts) { this.executionContexts = executionContexts; }

    @Transactional
    public ExecutionContext createExecution(Long workflowId){
        WorkflowVersion version=workflowVersionRepository.findTopByWorkflow_IdAndPublishedTrueOrderByVersionNumberDesc(workflowId)
                .orElseThrow(()->new IllegalStateException("No published version found"));
        return createExecutionForVersion(version.getId());
    }

    @Transactional
    public ExecutionContext createExecutionForVersion(Long workflowVersionId){
        return createExecutionForVersion(workflowVersionId, true, null);
    }

    @Transactional
    public ExecutionContext createExecutionForVersion(Long workflowVersionId, Integer deadlineSeconds) {
        return createExecutionForVersion(workflowVersionId, true, deadlineSeconds);
    }

    @Transactional
    public ExecutionContext createExecutionForReplay(Long workflowVersionId) {
        return createExecutionForVersion(workflowVersionId, false, null);
    }

    private ExecutionContext createExecutionForVersion(Long workflowVersionId, boolean requirePublished, Integer deadlineSeconds) {
        if (deadlineSeconds != null && (deadlineSeconds < 1 || deadlineSeconds > 604800))
            throw new IllegalArgumentException("deadlineSeconds must be between 1 and 604800");
        WorkflowVersion version=workflowVersionRepository.findById(workflowVersionId).orElseThrow(()->new RuntimeException("Workflow version not found"));
        if (requirePublished && !Boolean.TRUE.equals(version.getPublished())) {
            throw new IllegalStateException("Workflow version is not published: " + workflowVersionId);
        }
        List<List<Long>> stages=workflowGraphService.generateExecutionStages(workflowVersionId);

        if(stages.isEmpty()){
            throw new IllegalStateException("Workflow version has no task nodes");
        }
        Workflow lockedWorkflow = workflowRepository.findByIdForUpdate(version.getWorkflow().getId())
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
        Integer concurrencyLimit = lockedWorkflow.getMaxConcurrentExecutions();
        if (lockedWorkflow.getOwner() != null && maxConcurrentPerAccount > 0) {
            users.findByIdForUpdate(lockedWorkflow.getOwner().getId())
                    .orElseThrow(() -> new IllegalStateException("Workflow owner account is unavailable"));
            long ownerActive = workflowExecutionRepository.countActiveByOwner(lockedWorkflow.getOwner().getId(),
                    List.of(WorkflowExecutionStatus.PENDING, WorkflowExecutionStatus.RUNNING,
                            WorkflowExecutionStatus.PAUSED, WorkflowExecutionStatus.WAITING_FOR_HUMAN));
            if (ownerActive >= maxConcurrentPerAccount)
                throw new IllegalStateException("Account concurrent execution quota reached (" + maxConcurrentPerAccount + ")");
        }
        if (concurrencyLimit != null && concurrencyLimit > 0) {
            long active = workflowExecutionRepository.countActiveByWorkflow(lockedWorkflow.getId(),
                    List.of(WorkflowExecutionStatus.PENDING, WorkflowExecutionStatus.RUNNING,
                            WorkflowExecutionStatus.PAUSED, WorkflowExecutionStatus.WAITING_FOR_HUMAN));
            if (active >= concurrencyLimit)
                throw new IllegalStateException("Workflow concurrent execution limit reached (" + concurrencyLimit + ")");
        }
        WorkflowExecution workflowExecution=WorkflowExecution.builder()
                .workflowVersion(version).versionNumber(version.getVersionNumber()).status(WorkflowExecutionStatus.RUNNING)
                .startedAt(LocalDateTime.now()).initiatedBy(currentActor())
                .correlationId(java.util.UUID.randomUUID().toString())
                .priority(lockedWorkflow.getPriority() == null ? 0 : lockedWorkflow.getPriority())
                .deadlineAt(deadlineSeconds == null ? null : LocalDateTime.now().plusSeconds(deadlineSeconds)).build();

        workflowExecution=workflowExecutionRepository.save(workflowExecution);
        executionEvents.append(workflowExecution.getId(), null, "EXECUTION_STARTED", "RUNNING",
                "Started workflow version " + version.getVersionNumber());
        metrics.workflowStarted();
        com.aryan.conduit.execution.entity.ExecutionContextEntity executionContext = ExecutionContextEntity
                .builder().workflowExecution(workflowExecution).variableJson("{}").build();

        executionContextRepository.save(executionContext);
        if (environmentProfiles != null && executionContexts != null && version.getWorkflow().getEnvironmentProfileId() != null) {
            Map<String, Object> environment = environmentProfiles.valuesFor(version.getWorkflow().getEnvironmentProfileId(), version.getWorkflow().getId());
            executionContexts.initializeVariables(workflowExecution.getId(), environment);
        }

        Map<Long,TaskExecution> taskExecutionMap=new HashMap<>();

        for(List<Long> stage:stages){
            for(Long taskId:stage){
                TaskNode taskNode=taskNodeRepository.findById(taskId).orElseThrow(()->new RuntimeException("Task not found"));
                TaskExecution taskExecution = TaskExecution.builder()
                        .workflowExecution(workflowExecution)
                        .taskNode(taskNode)
                        .idempotencyKey(workflowExecution.getId() + ":" + taskNode.getId())
                        .status(TaskExecutionStatus.PENDING)
                        .retryCount(0)
                        .build();
                taskExecution=taskExecutionRepository.save(taskExecution);
                taskExecutionMap.put(taskId,taskExecution);
            }
        }
        return new ExecutionContext(workflowExecution,stages,taskExecutionMap);
    }

    private String currentActor() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())
                ? "system" : authentication.getName();
    }
}
