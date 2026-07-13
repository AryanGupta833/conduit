package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import com.aryan.conduit.workflow.dto.ExecutionStatusResponse;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import com.aryan.conduit.workflow.service.RuntimeWorkflowExecutor;
import com.aryan.conduit.workflow.service.WorkflowExecutionAsyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final ExecutionCreationService executionCreationService;
    private final TaskRunnerService taskRunnerService;
    private final RuntimeWorkflowExecutor runtimeWorkflowExecutor;
    private final WorkflowExecutionAsyncService workflowExecutionAsyncService;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowVersionRepository workflowVersionRepository;



    public Long startWorkflow(Long workflowId){
        System.out.println("WorkflowId received = " + workflowId);
        WorkflowVersion version=workflowVersionRepository.findByWorkflow_IdAndLatestTrue(workflowId).orElseThrow(
                ()->new IllegalStateException("No latest version found for workflow "+workflowId)
        );
        System.out.println("Found version id = " + version.getId());
        return startWorkflowVersion(version.getId());

    }

    public Long startWorkflowVersion(Long workflowVersionId){
        ExecutionContext context=executionCreationService.createExecutionForVersion(workflowVersionId);
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
                durationMs
        );
    }

}