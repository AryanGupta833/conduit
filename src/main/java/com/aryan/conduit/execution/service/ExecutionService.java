package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import com.aryan.conduit.workflow.dto.ExecutionStatusResponse;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.aryan.conduit.workflow.service.WorkflowExecutionAsyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final ExecutionCreationService executionCreationService;
    private final WorkflowExecutionAsyncService workflowExecutionAsyncService;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final ExecutionContextService executionContextService;



    public Long startWorkflow(Long workflowId){
        WorkflowVersion version=workflowVersionRepository.findTopByWorkflow_IdAndPublishedTrueOrderByVersionNumberDesc(workflowId).orElseThrow(
                ()->new IllegalStateException("No published version found for workflow "+workflowId)
        );
        return startWorkflowVersion(version.getId());

    }

    public Long startWorkflowVersion(Long workflowVersionId){
        ExecutionContext context=executionCreationService.createExecutionForVersion(workflowVersionId);
        workflowExecutionAsyncService.executeAsync(context.workflowExecution().getId());
        return context.workflowExecution().getId();

        }

    public Long startWorkflowVersion(Long workflowVersionId, Integer deadlineSeconds) {
        ExecutionContext context = executionCreationService.createExecutionForVersion(workflowVersionId, deadlineSeconds);
        workflowExecutionAsyncService.executeAsync(context.workflowExecution().getId());
        return context.workflowExecution().getId();
    }

    /** Starts a new execution of the exact immutable version used by an earlier run. */
    public Long rerunExecution(Long executionId) {
        WorkflowExecution previous = workflowExecutionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));
        if (previous.getStatus() == null || !java.util.Set.of(WorkflowExecutionStatus.SUCCESS,
                WorkflowExecutionStatus.FAILED, WorkflowExecutionStatus.CANCELLED,
                WorkflowExecutionStatus.TIMED_OUT).contains(previous.getStatus()))
            throw new IllegalStateException("Only a terminal workflow execution can be rerun");
        ExecutionContext context = executionCreationService.createExecutionForReplay(previous.getWorkflowVersion().getId());
        executionContextService.initializeVariables(context.workflowExecution().getId(), executionContextService.getInitialVariables(executionId));
        workflowExecutionAsyncService.executeAsync(context.workflowExecution().getId());
        return context.workflowExecution().getId();
    }


    public ExecutionStatusResponse getExecution(Long executionId) {

        WorkflowExecution execution = workflowExecutionRepository
                .findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found"));

        Long durationMs = null;

        if (execution.getFinishedAt() != null) {
            durationMs = java.time.Duration
                    .between(execution.getStartedAt(), execution.getFinishedAt())
                    .toMillis();
        }

        WorkflowVersion version = execution.getWorkflowVersion();

        return new ExecutionStatusResponse(
                execution.getId(),
                version.getWorkflow().getId(),
                version.getId(),
                version.getWorkflow().getName(),
                version.getVersionNumber(),
                execution.getStatus(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                durationMs,
                execution.getCorrelationId()
        );
    }

}
