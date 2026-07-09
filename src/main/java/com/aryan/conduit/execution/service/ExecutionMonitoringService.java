package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.ExecutionLogRepository;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.dto.ExecutionLogResponse;
import com.aryan.conduit.workflow.dto.TaskExecutionResponse;
import com.aryan.conduit.workflow.dto.WorkflowExecutionDetailResponse;
import com.aryan.conduit.workflow.dto.WorkflowExecutionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

import static java.util.Arrays.stream;

@Service
@RequiredArgsConstructor
public class ExecutionMonitoringService {


    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final ExecutionLogRepository executionLogRepository;

    public List<WorkflowExecutionResponse> getExecutions() {
        System.out.println("Count = "+workflowExecutionRepository.count());
        System.out.println(workflowExecutionRepository.findAll());

        return workflowExecutionRepository.findAllByOrderByIdDesc()
                .stream().map(execution->
                        new WorkflowExecutionResponse(
                                execution.getId(),
                                execution.getWorkflowVersion().getWorkflow().getId(),
                                execution.getWorkflowVersion().getId(),
                                execution.getVersionNumber(),
                                execution.getStatus(),
                                execution.getStartedAt(),
                                execution.getFinishedAt()
                        )).toList();
    }

    public WorkflowExecutionDetailResponse getExecution(
            Long executionId) {

        WorkflowExecution execution =
                workflowExecutionRepository
                        .findById(executionId)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Execution not found"));

        return new WorkflowExecutionDetailResponse(
                execution.getId(),
                execution.getWorkflowVersion().getWorkflow().getName(),
                execution.getStatus(),
                execution.getStartedAt(),
                execution.getFinishedAt()
        );
    }

    public List<TaskExecutionResponse> getTasks(
            Long executionId) {

        return taskExecutionRepository
                .findByWorkflowExecution_Id(executionId)
                .stream()
                .map(task ->
                        new TaskExecutionResponse(
                                task.getId(),
                                task.getTaskNode().getName(),
                                task.getStatus(),
                                task.getRetryCount()
                        )
                )
                .toList();
    }

    public List<ExecutionLogResponse> getLogs(
            Long executionId) {

        return executionLogRepository
                .findByTaskExecution_WorkflowExecution_Id(
                        executionId
                )
                .stream()
                .map(log ->
                        new ExecutionLogResponse(
                                log.getTimestamp(),
                                log.getLevel().name(),
                                log.getMessage()
                        )
                )
                .toList();
    }
}