package com.aryan.conduit.workflow.service;

import com.aryan.conduit.workflow.dto.CreateDependencyRequest;
import com.aryan.conduit.workflow.dto.UpdateDependencyRequest;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DependencyService {

    private final DependencyRepository dependencyRepository;
    private final TaskNodeRepository taskNodeRepository;

    @Transactional
    public Dependency createDependency(CreateDependencyRequest request) {

        TaskNode parent =
                taskNodeRepository.findById(request.parentTaskId())
                        .orElseThrow(() ->
                                new RuntimeException("Parent task not found"));

        TaskNode child =
                taskNodeRepository.findById(request.childTaskId())
                        .orElseThrow(() ->
                                new RuntimeException("Child task not found"));

        Dependency dependency =
                Dependency.builder()
                        .parent(parent)
                        .child(child)
                        .condition(request.condition())
                        .expression(request.expression())
                        .build();

        return dependencyRepository.save(dependency);
    }

    @Transactional
    public Dependency updateDependency(
            Long dependencyId,
            UpdateDependencyRequest request) {

        Dependency dependency =
                dependencyRepository.findById(dependencyId)
                        .orElseThrow(() ->
                                new RuntimeException("Dependency not found"));

        TaskNode parent =
                taskNodeRepository.findById(request.parentTaskId())
                        .orElseThrow(() ->
                                new RuntimeException("Parent task not found"));

        TaskNode child =
                taskNodeRepository.findById(request.childTaskId())
                        .orElseThrow(() ->
                                new RuntimeException("Child task not found"));

        dependency.setParent(parent);
        dependency.setChild(child);
        dependency.setCondition(request.condition());
        dependency.setExpression(request.expression());

        return dependencyRepository.save(dependency);
    }

    @Transactional
    public void deleteDependency(Long dependencyId) {

        if (!dependencyRepository.existsById(dependencyId)) {
            throw new RuntimeException("Dependency not found");
        }

        dependencyRepository.deleteById(dependencyId);
    }
}