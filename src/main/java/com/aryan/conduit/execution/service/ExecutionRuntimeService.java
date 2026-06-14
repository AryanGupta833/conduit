package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.ExpressionNode;
import com.aryan.conduit.execution.entity.JoinCondition;
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
    private final ExecutionContextService executionContextService;
    private final ExpressionEvaluator expressionEvaluator;



    public boolean canRun(Long workflowExecutionId,Long taskId, Map<Long, TaskExecutionStatus> taskStatuses){
        List<Dependency> dependencies=dependencyRepository.findByChild_Id(taskId);
        if(dependencies.isEmpty()){
            return true;
        }
        JoinCondition joinCondition=dependencies.get(0).getChild().getJoinCondition();

        if(joinCondition==JoinCondition.ALL_PARENTS){
            for(Dependency dependency:dependencies){
                if(!isDependencySatisfied(workflowExecutionId,dependency,taskStatuses)){
                    return false;
                }
            }
            return true;
        }

        for(Dependency dependency:dependencies){
            if(isDependencySatisfied(workflowExecutionId,dependency,taskStatuses)){
                return true;
            }
        }
        return false;


    }

    public RuntimeExecutionContext initializeContext(Long workflowId){
        RuntimeExecutionContext context=new RuntimeExecutionContext();
        List<Long> rootTasks=workflowGraphService.getRootTasks(workflowId);
        for(Long rootTask:rootTasks){
            context.getReadyQueue().offer(rootTask);
            context.getScheduledTasks().add(rootTask);
        }

        return context;
    }

    public void evaluateChildren(Long workflowExecutionId,Long completedTaskId,RuntimeExecutionContext context){
        System.out.println("Evaluating children for task "+completedTaskId);
        List<Dependency> dependencies=dependencyRepository.findByParent_Id(completedTaskId);
        for(Dependency dependency:dependencies){
            Long childId=dependency.getChild().getId();
            System.out.println("Dependency condition = "+dependency.getCondition());
            System.out.println("Expression = "+dependency.getExpression());
            System.out.println("Checking child "+childId);
            System.out.println("Calling canRun for child "+childId);
            boolean runnable=canRun(workflowExecutionId,childId,context.getTaskStatuses());

            if(runnable&&!context.getScheduledTasks().contains(childId)){
                context.getReadyQueue().offer(childId);
                context.getScheduledTasks().add(childId);
                System.out.println("Scheduled child "+childId);

            }
            else if(runnable){
                System.out.println("Child "+childId+" already scheduled");
            }

            System.out.println("canRun result = "+runnable);
        }
    }

    private boolean isDependencySatisfied(Long workflowExecutionId,Dependency dependency,Map<Long,TaskExecutionStatus> taskStatuses){
        Long parentId=dependency.getParent().getId();
        TaskExecutionStatus parentStatus=taskStatuses.get(parentId);
        if(parentStatus==null){
            return false;
        }
        switch (dependency.getCondition()){
            case ALWAYS :
                return parentStatus==TaskExecutionStatus.SUCCESS;

            case ON_SUCCESS:
                return parentStatus==TaskExecutionStatus.SUCCESS;

            case ON_FAILURE:
                return parentStatus==TaskExecutionStatus.FAILED;

            case EXPRESSION:
                Map<String,Object> variables=executionContextService.getVariables(workflowExecutionId);
                String expression=dependency.getExpression();
                ExpressionLexer lexer=new ExpressionLexer(expression);
                ExpressionParser parser=new ExpressionParser(lexer.tokenize());

                ExpressionNode ast=parser.parse();
                return (boolean) expressionEvaluator.evaluate(ast,variables);

            default:
                return false;
        }


    }

}
