package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.workflow.dto.CreateDependencyRequest;
import com.aryan.conduit.workflow.dto.UpdateDependencyRequest;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.service.DependencyService;
import com.aryan.conduit.workflow.service.WorkflowGraphService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dependencies")
@RequiredArgsConstructor
public class DependencyController {
    private final DependencyRepository dependencyRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final WorkflowGraphService workflowGraphService;
    private final DependencyService dependencyService;


    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public void  createDependency(
            @RequestBody CreateDependencyRequest request) {

        TaskNode parent = taskNodeRepository.findById(request.parentTaskId())
                .orElseThrow(() ->
                        new RuntimeException("Parent task not found"));

        TaskNode child = taskNodeRepository.findById(request.childTaskId())
                .orElseThrow(() ->
                        new RuntimeException("Child task not found"));

        if (parent.getId().equals(child.getId())) {
            throw new IllegalArgumentException(
                    "Task cannot depend on itself");
        }

        if (!parent.getWorkflowVersion().getId()
                .equals(child.getWorkflowVersion().getId())) {

            throw new IllegalArgumentException(
                    "Tasks belong to different workflow versions");
        }

        if (dependencyRepository.existsByParent_IdAndChild_Id(
                parent.getId(),
                child.getId())) {

            throw new IllegalStateException(
                    "Dependency already exists");
        }

        Dependency dependency = Dependency.builder()
                .parent(parent)
                .child(child)
                .condition(request.condition())
                .expression(request.expression())
                .build();

        Dependency saved = dependencyRepository.save(dependency);

        workflowGraphService.validateWorkflow(
                parent.getWorkflowVersion().getId());


    }
    @GetMapping("/{versionId}/roots")
    public List<Long> roots(@PathVariable("versionId") Long versionId){
        return workflowGraphService.getRootTasks(versionId);
    }

    @PutMapping("/{id}")
    public Dependency updateDependency(
            @PathVariable Long id,
            @RequestBody UpdateDependencyRequest request) {

        return dependencyService.updateDependency(id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteDependency(
            @PathVariable Long id) {

        dependencyService.deleteDependency(id);
    }
}
