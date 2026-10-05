package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.observability.ConduitMetrics;
import lombok.RequiredArgsConstructor;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.aryan.conduit.execution.workspace.ExecutionWorkspaceService;
import com.aryan.conduit.execution.repository.HumanTaskRequestRepository;
import com.aryan.conduit.execution.entity.HumanTaskStatus;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExecutionControlService {
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final ConduitMetrics metrics;
    private final DistributedWorkflowCoordinator coordinator;
    private final HumanTaskRequestRepository humanTaskRequests;
    private ExecutionWorkspaceService workspaceService;
    private final ExecutionEventService executionEvents;

    @Autowired(required = false)
    void setWorkspaceService(ExecutionWorkspaceService workspaceService) { this.workspaceService = workspaceService; }

    @Transactional
    public void pause(Long executionId){
        WorkflowExecution execution=workflowExecutionRepository.findByIdForUpdate(executionId).orElseThrow();
        if(execution.getStatus()== WorkflowExecutionStatus.RUNNING){
            execution.setStatus(WorkflowExecutionStatus.PAUSED);
            workflowExecutionRepository.save(execution);
            executionEvents.append(executionId, null, "EXECUTION_PAUSED", "PAUSED", null);
        }
    }

    @Transactional
    public void resume(Long executionId){
        coordinator.resumeWorkflow(executionId);
        if (workflowExecutionRepository.findById(executionId)
                .map(execution -> execution.getStatus() == WorkflowExecutionStatus.RUNNING).orElse(false))
            executionEvents.append(executionId, null, "EXECUTION_RESUMED", "RUNNING", null);
    }

    @Transactional
    public void cancel(Long executionId){
        WorkflowExecution execution=workflowExecutionRepository.findByIdForUpdate(executionId).orElseThrow();
        if(execution.getStatus()==WorkflowExecutionStatus.RUNNING||execution.getStatus()==WorkflowExecutionStatus.PAUSED||execution.getStatus()==WorkflowExecutionStatus.WAITING_FOR_HUMAN){
            execution.setStatus(WorkflowExecutionStatus.CANCELLED);
            execution.setFinishedAt(LocalDateTime.now());
            workflowExecutionRepository.save(execution);
            executionEvents.append(executionId, null, "EXECUTION_CANCELLED", "CANCELLED", "Cancelled by request");
            humanTaskRequests.cancelWaitingByWorkflow(executionId, HumanTaskStatus.WAITING_FOR_HUMAN,
                    HumanTaskStatus.CANCELLED, LocalDateTime.now());
            long duration = execution.getStartedAt() == null ? 0L
                    : Duration.between(execution.getStartedAt(), execution.getFinishedAt()).toNanos();
            metrics.workflowFinished("CANCELLED", duration);
        }
    }

    @Transactional
    public void timeout(Long executionId) {
        WorkflowExecution execution = workflowExecutionRepository.findByIdForUpdate(executionId).orElseThrow();
        if (execution.getStatus() != WorkflowExecutionStatus.RUNNING
                && execution.getStatus() != WorkflowExecutionStatus.PAUSED
                && execution.getStatus() != WorkflowExecutionStatus.WAITING_FOR_HUMAN) return;
        execution.setStatus(WorkflowExecutionStatus.TIMED_OUT);
        execution.setFinishedAt(LocalDateTime.now());
        workflowExecutionRepository.save(execution);
        humanTaskRequests.cancelWaitingByWorkflow(executionId, HumanTaskStatus.WAITING_FOR_HUMAN,
                HumanTaskStatus.CANCELLED, LocalDateTime.now());
        executionEvents.append(executionId, null, "EXECUTION_TIMED_OUT", "TIMED_OUT", "Workflow deadline exceeded");
    }
}
