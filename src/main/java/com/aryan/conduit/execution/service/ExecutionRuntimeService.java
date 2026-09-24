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
    private final ExpressionEvaluator expressionEvaluator;
    private final ExpressionContextService expressionContextService;



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

    public RuntimeExecutionContext initializeContext(Long workflowVersionId){
        RuntimeExecutionContext context=new RuntimeExecutionContext();
        List<Long> rootTasks=workflowGraphService.getRootTasks(workflowVersionId);
        for(Long rootTask:rootTasks){
            context.getReadyQueue().offer(rootTask);
            context.getScheduledTasks().add(rootTask);
        }

        return context;
    }

    public void evaluateChildren(
            Long workflowExecutionId,
            Long completedTaskId,
            RuntimeExecutionContext context
    ) {

        List<Dependency> dependencies =
                dependencyRepository.findByParent_Id(
                        completedTaskId
                );

        for (Dependency dependency : dependencies) {

            Long childId =
                    dependency.getChild().getId();

            if (context.getScheduledTasks().contains(childId)) {
                continue;
            }

            boolean runnable =
                    canRun(
                            workflowExecutionId,
                            childId,
                            context.getTaskStatuses()
                    );

            if (runnable) {

                context.getReadyQueue().offer(childId);
                context.getScheduledTasks().add(childId);

                continue;
            }

            /*
             * Do not skip the child yet if another parent
             * could still satisfy its dependency.
             */
            if (allParentsTerminal(
                    childId,
                    context.getTaskStatuses()
            )) {

                context.getTaskStatuses().put(
                        childId,
                        TaskExecutionStatus.SKIPPED
                );

                context.getScheduledTasks().add(childId);

                /*
                 * A skipped task is still a completed runtime
                 * event, so evaluate its children as well.
                 */
                evaluateChildren(
                        workflowExecutionId,
                        childId,
                        context
                );
            }
        }
    }
    private boolean allParentsTerminal(
            Long childId,
            Map<Long, TaskExecutionStatus> taskStatuses
    ) {

        List<Dependency> dependencies =
                dependencyRepository.findByChild_Id(
                        childId
                );

        if (dependencies.isEmpty()) {
            return true;
        }

        for (Dependency dependency : dependencies) {

            Long parentId =
                    dependency.getParent().getId();

            TaskExecutionStatus status =
                    taskStatuses.get(parentId);

            if (status == null || !isTerminal(status)) {
                return false;
            }
        }

        return true;
    }
    private boolean isTerminal(
            TaskExecutionStatus status
    ) {

        return status == TaskExecutionStatus.SUCCESS
                || status == TaskExecutionStatus.FAILED
                || status == TaskExecutionStatus.SKIPPED
                || status == TaskExecutionStatus.TIMEOUT;
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

                Map<String, Object> variables =
                        expressionContextService.buildContext(
                                workflowExecutionId
                        );

                String expression =
                        dependency.getExpression();

                ExpressionLexer lexer =
                        new ExpressionLexer(expression);

                ExpressionParser parser =
                        new ExpressionParser(
                                lexer.tokenize()
                        );

                ExpressionNode ast =
                        parser.parse();

                Object result =
                        expressionEvaluator.evaluate(
                                ast,
                                variables
                        );

                if (!(result instanceof Boolean)) {
                    throw new IllegalArgumentException(
                            "Expression must evaluate to boolean: "
                                    + expression
                    );
                }

                return (Boolean) result;
            default:
                return false;
        }


    }

}
