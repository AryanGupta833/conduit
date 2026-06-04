package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.workflow.dto.RuntimeExecutionContext;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.service.WorkflowGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ExecutionRuntimeService {
    private final DependencyRepository dependencyRepository;
    private final WorkflowGraphService workflowGraphService;


    public boolean canRun(Long taskId, Map<Long, TaskExecutionStatus> taskStatuses){
        List<Dependency> dependencies=dependencyRepository.findByChild_Id(taskId);
        for(Dependency dependency:dependencies){
            Long parentId=dependency.getParent().getId();
            TaskExecutionStatus parentStatus=taskStatuses.get(parentId);

            if(parentStatus==null){
                return false;
            }
            switch (dependency.getCondition()){
                case ALWAYS -> {
                    if(parentStatus!=TaskExecutionStatus.SUCCESS){
                        return false;
                    }
                }
                case ON_SUCCESS -> {
                    if(parentStatus!=TaskExecutionStatus.SUCCESS){
                        return false;
                    }
                }
                case ON_FAILURE -> {
                    if(parentStatus!=TaskExecutionStatus.FAILED){
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public RuntimeExecutionContext initializeContext(Long workflowId){
        RuntimeExecutionContext context=new RuntimeExecutionContext();
        List<Long> rootTasks=workflowGraphService.getRootTasks(workflowId);
        context.getReadyQueue().addAll(rootTasks);
        return context;
    }

    public void evaluateChildren(Long completedTaskId,RuntimeExecutionContext context){
        List<Dependency> dependencies=dependencyRepository.findByParent_Id(completedTaskId);
        for(Dependency dependency:dependencies){
            Long childId=dependency.getChild().getId();
            if(canRun(childId,context.getTaskStatuses())){
                context.getReadyQueue().offer(childId);
            }
        }
    }

}
