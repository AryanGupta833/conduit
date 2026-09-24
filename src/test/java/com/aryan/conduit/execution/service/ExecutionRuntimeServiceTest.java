package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.DependencyCondition;
import com.aryan.conduit.execution.entity.JoinCondition;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.workflow.dto.RuntimeExecutionContext;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.service.WorkflowGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionRuntimeServiceTest {

    private DependencyRepository dependencyRepository;
    private WorkflowGraphService workflowGraphService;
    private ExpressionContextService expressionContextService;
    private ExpressionEvaluator expressionEvaluator;

    private ExecutionRuntimeService runtimeService;

    @BeforeEach
    void setUp() {

        dependencyRepository =
                mock(DependencyRepository.class);

        workflowGraphService =
                mock(WorkflowGraphService.class);

        expressionContextService =
                mock(ExpressionContextService.class);

        expressionEvaluator =
                new ExpressionEvaluator();

        runtimeService =
                new ExecutionRuntimeService(
                        dependencyRepository,
                        workflowGraphService,
                        expressionEvaluator,
                        expressionContextService
                );
    }

    @Test
    void shouldAllowChildWhenExpressionIsTrue() {

        Long workflowExecutionId = 1L;
        Long parentTaskId = 10L;
        Long childTaskId = 20L;

        TaskNode parent =
                TaskNode.builder()
                        .id(parentTaskId)
                        .name("fetchUser")
                        .build();

        TaskNode child =
                TaskNode.builder()
                        .id(childTaskId)
                        .name("adultTask")
                        .joinCondition(
                                JoinCondition.ALL_PARENTS
                        )
                        .build();

        Dependency dependency =
                mock(Dependency.class);

        when(dependency.getParent())
                .thenReturn(parent);

        when(dependency.getChild())
                .thenReturn(child);

        when(dependency.getCondition())
                .thenReturn(
                        DependencyCondition.EXPRESSION
                );

        when(dependency.getExpression())
                .thenReturn(
                        "fetchUser.output.userId >= 18"
                );

        when(
                dependencyRepository.findByChild_Id(
                        childTaskId
                )
        ).thenReturn(List.of(dependency));

        when(
                expressionContextService.buildContext(
                        workflowExecutionId
                )
        ).thenReturn(
                Map.of(
                        "fetchUser",
                        Map.of(
                                "output",
                                Map.of(
                                        "userId", 25
                                )
                        )
                )
        );

        Map<Long, TaskExecutionStatus> statuses =
                Map.of(
                        parentTaskId,
                        TaskExecutionStatus.SUCCESS
                );

        boolean result =
                runtimeService.canRun(
                        workflowExecutionId,
                        childTaskId,
                        statuses
                );

        assertTrue(result);
    }

    @Test
    void shouldRejectChildWhenExpressionIsFalse() {

        Long workflowExecutionId = 1L;
        Long parentTaskId = 10L;
        Long childTaskId = 20L;

        TaskNode parent =
                TaskNode.builder()
                        .id(parentTaskId)
                        .name("fetchUser")
                        .build();

        TaskNode child =
                TaskNode.builder()
                        .id(childTaskId)
                        .name("adultTask")
                        .joinCondition(
                                JoinCondition.ALL_PARENTS
                        )
                        .build();

        Dependency dependency =
                mock(Dependency.class);

        when(dependency.getParent())
                .thenReturn(parent);

        when(dependency.getChild())
                .thenReturn(child);

        when(dependency.getCondition())
                .thenReturn(
                        DependencyCondition.EXPRESSION
                );

        when(dependency.getExpression())
                .thenReturn(
                        "fetchUser.output.userId >= 18"
                );

        when(
                dependencyRepository.findByChild_Id(
                        childTaskId
                )
        ).thenReturn(List.of(dependency));

        when(
                expressionContextService.buildContext(
                        workflowExecutionId
                )
        ).thenReturn(
                Map.of(
                        "fetchUser",
                        Map.of(
                                "output",
                                Map.of(
                                        "userId", 15
                                )
                        )
                )
        );

        Map<Long, TaskExecutionStatus> statuses =
                Map.of(
                        parentTaskId,
                        TaskExecutionStatus.SUCCESS
                );

        boolean result =
                runtimeService.canRun(
                        workflowExecutionId,
                        childTaskId,
                        statuses
                );

        assertFalse(result);
    }

    @Test
    void shouldScheduleOnlyAdultBranchWhenUserIsAdult() {

        Long workflowExecutionId = 1L;
        Long parentTaskId = 10L;
        Long adultTaskId = 20L;
        Long minorTaskId = 30L;

        TaskNode parent =
                TaskNode.builder()
                        .id(parentTaskId)
                        .name("fetchUser")
                        .build();

        TaskNode adultTask =
                TaskNode.builder()
                        .id(adultTaskId)
                        .name("adultTask")
                        .joinCondition(JoinCondition.ALL_PARENTS)
                        .build();

        TaskNode minorTask =
                TaskNode.builder()
                        .id(minorTaskId)
                        .name("minorTask")
                        .joinCondition(JoinCondition.ALL_PARENTS)
                        .build();

        Dependency adultDependency =
                mock(Dependency.class);

        when(adultDependency.getParent())
                .thenReturn(parent);

        when(adultDependency.getChild())
                .thenReturn(adultTask);

        when(adultDependency.getCondition())
                .thenReturn(DependencyCondition.EXPRESSION);

        when(adultDependency.getExpression())
                .thenReturn(
                        "fetchUser.output.userId >= 18"
                );

        Dependency minorDependency =
                mock(Dependency.class);

        when(minorDependency.getParent())
                .thenReturn(parent);

        when(minorDependency.getChild())
                .thenReturn(minorTask);

        when(minorDependency.getCondition())
                .thenReturn(DependencyCondition.EXPRESSION);

        when(minorDependency.getExpression())
                .thenReturn(
                        "fetchUser.output.userId < 18"
                );

        when(
                dependencyRepository.findByParent_Id(
                        parentTaskId
                )
        ).thenReturn(
                List.of(
                        adultDependency,
                        minorDependency
                )
        );

        when(
                dependencyRepository.findByChild_Id(
                        adultTaskId
                )
        ).thenReturn(
                List.of(adultDependency)
        );

        when(
                dependencyRepository.findByChild_Id(
                        minorTaskId
                )
        ).thenReturn(
                List.of(minorDependency)
        );

        when(
                expressionContextService.buildContext(
                        workflowExecutionId
                )
        ).thenReturn(
                Map.of(
                        "fetchUser",
                        Map.of(
                                "output",
                                Map.of(
                                        "userId", 25
                                )
                        )
                )
        );

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getTaskStatuses().put(
                parentTaskId,
                TaskExecutionStatus.SUCCESS
        );

        runtimeService.evaluateChildren(
                workflowExecutionId,
                parentTaskId,
                context
        );

        assertTrue(
                context.getReadyQueue().contains(
                        adultTaskId
                )
        );

        assertFalse(
                context.getReadyQueue().contains(
                        minorTaskId
                )
        );

        assertTrue(
                context.getScheduledTasks().contains(
                        adultTaskId
                )
        );

        assertTrue(
                context.getScheduledTasks().contains(
                        minorTaskId
                )
        );

        assertEquals(
                TaskExecutionStatus.SKIPPED,
                context.getTaskStatuses().get(minorTaskId)
        );
    }

    @Test
    void shouldScheduleOnlyMinorBranchWhenUserIsMinor() {

        Long workflowExecutionId = 1L;
        Long parentTaskId = 10L;
        Long adultTaskId = 20L;
        Long minorTaskId = 30L;

        TaskNode parent =
                TaskNode.builder()
                        .id(parentTaskId)
                        .name("fetchUser")
                        .build();

        TaskNode adultTask =
                TaskNode.builder()
                        .id(adultTaskId)
                        .name("adultTask")
                        .joinCondition(JoinCondition.ALL_PARENTS)
                        .build();

        TaskNode minorTask =
                TaskNode.builder()
                        .id(minorTaskId)
                        .name("minorTask")
                        .joinCondition(JoinCondition.ALL_PARENTS)
                        .build();

        Dependency adultDependency =
                mock(Dependency.class);

        when(adultDependency.getParent())
                .thenReturn(parent);

        when(adultDependency.getChild())
                .thenReturn(adultTask);

        when(adultDependency.getCondition())
                .thenReturn(DependencyCondition.EXPRESSION);

        when(adultDependency.getExpression())
                .thenReturn(
                        "fetchUser.output.userId >= 18"
                );

        Dependency minorDependency =
                mock(Dependency.class);

        when(minorDependency.getParent())
                .thenReturn(parent);

        when(minorDependency.getChild())
                .thenReturn(minorTask);

        when(minorDependency.getCondition())
                .thenReturn(DependencyCondition.EXPRESSION);

        when(minorDependency.getExpression())
                .thenReturn(
                        "fetchUser.output.userId < 18"
                );

        when(
                dependencyRepository.findByParent_Id(
                        parentTaskId
                )
        ).thenReturn(
                List.of(
                        adultDependency,
                        minorDependency
                )
        );

        when(
                dependencyRepository.findByChild_Id(
                        adultTaskId
                )
        ).thenReturn(
                List.of(adultDependency)
        );

        when(
                dependencyRepository.findByChild_Id(
                        minorTaskId
                )
        ).thenReturn(
                List.of(minorDependency)
        );

        when(
                expressionContextService.buildContext(
                        workflowExecutionId
                )
        ).thenReturn(
                Map.of(
                        "fetchUser",
                        Map.of(
                                "output",
                                Map.of(
                                        "userId", 15
                                )
                        )
                )
        );

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getTaskStatuses().put(
                parentTaskId,
                TaskExecutionStatus.SUCCESS
        );

        runtimeService.evaluateChildren(
                workflowExecutionId,
                parentTaskId,
                context
        );

        assertFalse(
                context.getReadyQueue().contains(
                        adultTaskId
                )
        );

        assertTrue(
                context.getReadyQueue().contains(
                        minorTaskId
                )
        );

        assertTrue(
                context.getScheduledTasks().contains(
                        adultTaskId
                )
        );

        assertEquals(
                TaskExecutionStatus.SKIPPED,
                context.getTaskStatuses().get(adultTaskId)
        );

        assertTrue(
                context.getScheduledTasks().contains(
                        minorTaskId
                )
        );
    }

    @Test
    void shouldSkipChildWhenNoExpressionBranchMatches() {

        Long workflowExecutionId = 1L;
        Long parentTaskId = 10L;
        Long adultTaskId = 20L;
        Long minorTaskId = 30L;

        TaskNode parent =
                TaskNode.builder()
                        .id(parentTaskId)
                        .name("fetchUser")
                        .build();

        TaskNode adultTask =
                TaskNode.builder()
                        .id(adultTaskId)
                        .name("adultTask")
                        .joinCondition(JoinCondition.ALL_PARENTS)
                        .build();

        TaskNode minorTask =
                TaskNode.builder()
                        .id(minorTaskId)
                        .name("minorTask")
                        .joinCondition(JoinCondition.ALL_PARENTS)
                        .build();

        Dependency adultDependency =
                mock(Dependency.class);

        when(adultDependency.getParent())
                .thenReturn(parent);

        when(adultDependency.getChild())
                .thenReturn(adultTask);

        when(adultDependency.getCondition())
                .thenReturn(DependencyCondition.EXPRESSION);

        when(adultDependency.getExpression())
                .thenReturn(
                        "fetchUser.output.userId >= 18"
                );

        Dependency minorDependency =
                mock(Dependency.class);

        when(minorDependency.getParent())
                .thenReturn(parent);

        when(minorDependency.getChild())
                .thenReturn(minorTask);

        when(minorDependency.getCondition())
                .thenReturn(DependencyCondition.EXPRESSION);

        when(minorDependency.getExpression())
                .thenReturn(
                        "fetchUser.output.userId < 13"
                );

        when(
                dependencyRepository.findByParent_Id(
                        parentTaskId
                )
        ).thenReturn(
                List.of(
                        adultDependency,
                        minorDependency
                )
        );

        when(
                dependencyRepository.findByChild_Id(
                        adultTaskId
                )
        ).thenReturn(
                List.of(adultDependency)
        );

        when(
                dependencyRepository.findByChild_Id(
                        minorTaskId
                )
        ).thenReturn(
                List.of(minorDependency)
        );

        when(
                expressionContextService.buildContext(
                        workflowExecutionId
                )
        ).thenReturn(
                Map.of(
                        "fetchUser",
                        Map.of(
                                "output",
                                Map.of(
                                        "userId", 15
                                )
                        )
                )
        );

        RuntimeExecutionContext context =
                new RuntimeExecutionContext();

        context.getTaskStatuses().put(
                parentTaskId,
                TaskExecutionStatus.SUCCESS
        );

        runtimeService.evaluateChildren(
                workflowExecutionId,
                parentTaskId,
                context
        );

        assertFalse(
                context.getReadyQueue().contains(
                        adultTaskId
                )
        );

        assertFalse(
                context.getReadyQueue().contains(
                        minorTaskId
                )
        );

        assertEquals(
                TaskExecutionStatus.SKIPPED,
                context.getTaskStatuses()
                        .get(adultTaskId)
        );

        assertEquals(
                TaskExecutionStatus.SKIPPED,
                context.getTaskStatuses()
                        .get(minorTaskId)
        );
    }
}