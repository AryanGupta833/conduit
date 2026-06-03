package com.aryan.conduit.execution.service;


import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.entity.WorkflowExecutionStatus;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.dto.ExecutionContext;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
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

    @Transactional
    public ExecutionContext createExecution(Long workflowId){

        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() ->
                        new RuntimeException("Workflow not found"));

        List<List<Long>> stages =
                workflowGraphService.generateExecutionStages(workflowId);

        WorkflowExecution workflowExecution =
                WorkflowExecution.builder()
                        .workflow(workflow)
                        .status(WorkflowExecutionStatus.RUNNING)
                        .startedAt(LocalDateTime.now())
                        .build();

        workflowExecution =
                workflowExecutionRepository.save(workflowExecution);

        Map<Long, TaskExecution> taskExecutionMap =
                new HashMap<>();

        for(List<Long> stage : stages){

            for(Long taskId : stage){

                TaskNode taskNode =
                        taskNodeRepository.findById(taskId)
                                .orElseThrow(() ->
                                        new RuntimeException("Task not found"));

                TaskExecution taskExecution =
                        TaskExecution.builder()
                                .workflowExecution(workflowExecution)
                                .taskNode(taskNode)
                                .status(TaskExecutionStatus.PENDING)
                                .retryCount(0)
                                .build();

                TaskExecution saved =
                        taskExecutionRepository.save(taskExecution);

                taskExecutionMap.put(
                        taskNode.getId(),
                        saved
                );
            }
        }

        return new ExecutionContext(
                workflowExecution,
                stages,
                taskExecutionMap
        );
    }
}