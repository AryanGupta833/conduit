package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.entity.DependencyCondition;
import com.aryan.conduit.execution.entity.TaskExecutionStatus;
import com.aryan.conduit.execution.service.ExecutionRuntimeService;
import com.aryan.conduit.workflow.dto.CreateDependencyRequest;
import com.aryan.conduit.workflow.dto.RuntimeExecutionContext;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.service.WorkflowGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dependencies")
@RequiredArgsConstructor
public class DependencyController {
    private final DependencyRepository dependencyRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final WorkflowGraphService workflowGraphService;
    private final ExecutionRuntimeService executionRuntimeService;


    @PostMapping
    public Dependency createDependency(
            @RequestBody CreateDependencyRequest request
            )
    {
        TaskNode parent=taskNodeRepository
                .findById(request.parentTaskId()).orElseThrow(()->new RuntimeException("Parent task not found"));

        TaskNode child=taskNodeRepository
                .findById(request.childTaskId()).orElseThrow(()->new RuntimeException("Child task not found"));

        Dependency dependency=Dependency.builder()
                .parent(parent)
                .child(child)
                .condition(request.condition())
                .expression(request.expression())
                .build();

        return dependencyRepository.save(dependency);
    }

    @GetMapping("/{workflowId}/roots")
    public List<Long> roots(@PathVariable Long workflowId){
        return workflowGraphService.getRootTasks(workflowId);
    }

//    @GetMapping("/unlock")
//    public Object unlock(){
//        RuntimeExecutionContext context= executionRuntimeService.initializeContext(6L);
//        context.getTaskStatuses().put(15L, TaskExecutionStatus.FAILED);
//        executionRuntimeService.evaluateChildren(15L,context);
//
//        return context.getReadyQueue();
//    }

    @GetMapping("/test18")
    public Boolean test18(){
        Map<Long,TaskExecutionStatus> statuses=new HashMap<>();
        statuses.put(16L,TaskExecutionStatus.SUCCESS);
        statuses.put(17L,TaskExecutionStatus.SUCCESS);
        return executionRuntimeService.canRun(336L,18L,statuses);
    }
}
