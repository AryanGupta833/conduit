package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.*;
import com.aryan.conduit.execution.repository.ExecutionContextRepository;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
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

    @Transactional
    public ExecutionContext createExecution(Long workflowId){
        WorkflowVersion version=workflowVersionRepository.findByWorkflow_IdAndLatestTrue(workflowId)
                .orElseThrow(()->new IllegalStateException("No published version found"));
        return createExecutionForVersion(version.getId());
    }

    @Transactional
    public ExecutionContext createExecutionForVersion(Long workflowVersionId){
        WorkflowVersion version=workflowVersionRepository.findById(workflowVersionId).orElseThrow(()->new RuntimeException("Workflow version not found"));
        List<List<Long>> stages=workflowGraphService.generateExecutionStages(workflowVersionId);

        if(stages.isEmpty()){
            throw new IllegalStateException("Workflow version has no task nodes");
        }
        WorkflowExecution workflowExecution=WorkflowExecution.builder()
                .workflowVersion(version).versionNumber(version.getVersionNumber()).status(WorkflowExecutionStatus.RUNNING)
                .startedAt(LocalDateTime.now()).build();

        workflowExecution=workflowExecutionRepository.save(workflowExecution);
        com.aryan.conduit.execution.entity.ExecutionContextEntity executionContext = ExecutionContextEntity
                .builder().workflowExecution(workflowExecution).variableJson("{}").build();

        executionContextRepository.save(executionContext);

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
}