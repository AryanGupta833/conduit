package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.workflow.dto.CreateDependencyRequest;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dependencies")
@RequiredArgsConstructor
public class DependencyController {
    private final DependencyRepository dependencyRepository;
    private final TaskNodeRepository taskNodeRepository;

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
                .build();

        return dependencyRepository.save(dependency);
    }
}
