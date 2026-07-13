package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecution;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.dto.ExecutionGraphEdge;
import com.aryan.conduit.workflow.dto.ExecutionGraphNode;
import com.aryan.conduit.workflow.dto.ExecutionGraphResponse;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class ExecutionGraphService {
    private final WorkflowExecutionRepository workflowExecutionRepository;

    private final TaskExecutionRepository taskExecutionRepository;

    private final DependencyRepository dependencyRepository;

    private final TaskNodeRepository taskNodeRepository;

    public ExecutionGraphResponse getGraph(Long executionId){
        WorkflowExecution execution =
                workflowExecutionRepository.findById(executionId)
                        .orElseThrow(() ->
                                new RuntimeException("Execution not found"));
        Long versionId =
                execution
                        .getWorkflowVersion()
                        .getId();
        List<TaskNode> tasks =
                taskNodeRepository
                        .findByWorkflowVersion_Id(versionId);

        List<Dependency> dependencies =
                dependencyRepository
                        .findByParent_WorkflowVersion_Id(versionId);
        List<TaskExecution> executions =
                taskExecutionRepository
                        .findByWorkflowExecution_Id(executionId);
        Map<Long, TaskExecutionStatus> statusMap =
                executions.stream()
                        .collect(Collectors.toMap(

                                e -> e.getTaskNode().getId(),

                                TaskExecution::getStatus

                        ));
        List<ExecutionGraphNode> nodes =
                tasks.stream()
                        .map(task ->

                                new ExecutionGraphNode(

                                        task.getId(),

                                        task.getName(),

                                        task.getXPosition(),

                                        task.getYPosition(),

                                        task.getPluginType(),

                                        statusMap.getOrDefault(

                                                task.getId(),

                                                TaskExecutionStatus.PENDING

                                        )

                                )

                        )
                        .toList();
        List<ExecutionGraphEdge> edges =
                dependencies.stream()
                        .map(d ->

                                new ExecutionGraphEdge(

                                        d.getId(),

                                        d.getParent().getId(),

                                        d.getChild().getId()

                                )

                        )
                        .toList();

        return new ExecutionGraphResponse(

                nodes,

                edges

        );
    }
}
