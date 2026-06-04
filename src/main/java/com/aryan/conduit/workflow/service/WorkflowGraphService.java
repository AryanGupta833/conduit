package com.aryan.conduit.workflow.service;


import com.aryan.conduit.common.util.DagValidator;
import com.aryan.conduit.workflow.dto.ExecutionPlan;
import com.aryan.conduit.workflow.dto.GraphEdgeResponse;
import com.aryan.conduit.workflow.dto.GraphNodeResponse;
import com.aryan.conduit.workflow.dto.WorkflowGraphResponse;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.scheduling.config.Task;
import org.springframework.stereotype.Service;

import javax.swing.event.DocumentEvent;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkflowGraphService {
    private final TaskNodeRepository taskNodeRepository;
    private final DependencyRepository dependencyRepository;
    private final DagValidator dagValidator;

    private Map<Long, List<Long> > buildGraph(Long workflowId){
        List<TaskNode> tasks=taskNodeRepository.findByWorkflow_Id(workflowId);

        List<Dependency> dependencies=dependencyRepository.findByParent_Workflow_Id(workflowId);

        Map<Long,List<Long>> graph=new HashMap<>();

        for(TaskNode task:tasks){
            graph.put(task.getId(),new ArrayList<>());
        }

        for(Dependency dependency:dependencies){
            Long parentId=dependency.getParent().getId();
            Long childId=dependency.getChild().getId();

            graph.get(parentId).add(childId);
        }
        return graph;

    }

    public void validateWorkflow(Long workflowId){
        Map<Long,List<Long>> graph=buildGraph(workflowId);

        if(dagValidator.hashCycle(graph)){
            throw  new IllegalStateException("Workflow contains cycle");
        }
    }

    public ExecutionPlan generateExecutionPlan(Long workflowId){
        Map<Long,List<Long>> graph=buildGraph(workflowId);
        if(dagValidator.hashCycle(graph)){
            throw new IllegalStateException("Workflow contains cycle");
        }
        List<Long> executionOrder=dagValidator.topologicalSort(graph);
        return new ExecutionPlan(workflowId,executionOrder);
    }

    public List<List<Long>> generateExecutionStages(Long workflowId)
    {
        Map<Long,List<Long>> graph=buildGraph(workflowId);

        if(dagValidator.hashCycle(graph)){
            throw new IllegalStateException("Workflow contains cycle");
        }

        Map<Long,Integer> indegree=new HashMap<>();

        for(Long node:graph.keySet()){
            indegree.putIfAbsent(node,0);

            for(Long neighbor:graph.get(node)){
                indegree.put(
                        neighbor,
                        indegree.getOrDefault(neighbor,0)+1
                );
            }
        }
        Queue<Long> queue=new LinkedList<>();
        for(Map.Entry<Long,Integer> entry:indegree.entrySet()){
            if(entry.getValue()==0){
                queue.offer(entry.getKey());
            }
        }

        List<List<Long>> stages=new ArrayList<>();

        while (!queue.isEmpty()){
            int size=queue.size();
            List<Long> currentStage=new ArrayList<>();
            for(int i=0;i<size;i++){
                Long current=queue.poll();
                currentStage.add(current);

                for(Long neighbor:graph.getOrDefault(current,Collections.emptyList())){
                    indegree.put(neighbor,indegree.get(neighbor)-1);

                    if(indegree.get(neighbor)==0){
                        queue.offer(neighbor);
                    }
                }
            }
            stages.add(currentStage);
        }
        return stages;
     }

     public WorkflowGraphResponse getWorkflowGraph(Long workflowId){
        List<TaskNode> tasks=taskNodeRepository.findByWorkflow_Id(workflowId);

        List<Dependency> dependencies=dependencyRepository.findByParent_Workflow_Id(workflowId);
        List<GraphNodeResponse> nodes=tasks.stream().map(task->new GraphNodeResponse(task.getId(), task.getName())).toList();
        List<GraphEdgeResponse> edges=dependencies.stream().map(dependency -> new GraphEdgeResponse(dependency.getParent().getId(),dependency.getChild().getId())).toList();

        return new WorkflowGraphResponse(nodes,edges);
     }

     public List<Long> getRootTasks(Long workflowId){
        List<TaskNode> tasks=taskNodeRepository.findByWorkflow_Id(workflowId);
        Set<Long> childTaskIds=dependencyRepository.findAll().stream().map(dependency -> dependency.getChild().getId()).collect(Collectors.toSet());

        return tasks.stream().map(TaskNode::getId).filter(taskId->!childTaskIds.contains(taskId)).toList();
     }
}
