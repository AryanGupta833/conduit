package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.dto.ExecutionSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExecutionSummaryService
{
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final TaskExecutionRepository taskExecutionRepository;

    public ExecutionSummaryResponse getSummary(Long workflowExecutionId){
        WorkflowExecution workflowExecution=workflowExecutionRepository.findById(workflowExecutionId).orElseThrow();
        List<TaskExecution> tasks=taskExecutionRepository.findByWorkflowExecution_Id(workflowExecutionId);
        int success=(int) tasks.stream().filter(t->t.getStatus()== TaskExecutionStatus.SUCCESS).count();
        int failed=(int) tasks.stream().filter(t->t.getStatus()==TaskExecutionStatus.FAILED).count();
        int skipped=(int) tasks.stream().filter(t->t.getStatus()==TaskExecutionStatus.SKIPPED).count();

        int total= tasks.size();

        Long duration=null;
        if(workflowExecution.getFinishedAt()!=null){
            duration= Duration.between(workflowExecution.getStartedAt(),workflowExecution.getFinishedAt()).toMillis();
        }

        return ExecutionSummaryResponse.builder()
                .workflowExecutionId(workflowExecution.getId())
                .workflowId(workflowExecution.getWorkflowVersion().getWorkflow().getId())
                .workflowVersionId(workflowExecution.getWorkflowVersion().getId())
                .versionNumber(workflowExecution.getVersionNumber())
                .workflowName(workflowExecution.getWorkflowVersion().getWorkflow().getName())
                .status(workflowExecution.getStatus().name())
                .totalTasks(total)
                .successfulTasks(success)
                .failedTasks(failed)
                .skippedTasks(skipped)
                .startedAt(workflowExecution.getStartedAt())
                .finishedAt(workflowExecution.getFinishedAt())
                .durationMs(duration)
                .build();

    }


}
