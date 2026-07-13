package com.aryan.conduit.workflow.service;

import com.aryan.conduit.workflow.dto.TaskDifference;
import com.aryan.conduit.workflow.dto.WorkflowVersionDiffResponse;
import com.aryan.conduit.workflow.dto.WorkflowVersionResponse;
import com.aryan.conduit.workflow.entity.Dependency;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkflowVersionService {
    private final WorkflowVersionRepository workflowVersionRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final DependencyRepository dependencyRepository;

    @Transactional
    public WorkflowVersion createVersion(Long workflowId) {
        WorkflowVersion latestVersion = workflowVersionRepository.findTopByWorkflow_IdOrderByVersionNumberDesc(workflowId).orElseThrow(() -> new RuntimeException("Workflow not found"));
        latestVersion.setLatest(false);
        workflowVersionRepository.save(latestVersion);

        WorkflowVersion newVersion = WorkflowVersion.builder().
                workflow(latestVersion.getWorkflow()).versionNumber(latestVersion.getVersionNumber() + 1)
                .published(false).latest(true).createdAt(LocalDateTime.now()).
                build();

        newVersion = workflowVersionRepository.save(newVersion);
        List<TaskNode> oldTasks = taskNodeRepository.findByWorkflowVersion_Id(latestVersion.getId());
        Map<Long, TaskNode> taskMapping = new HashMap<>();

        for (TaskNode oldTask : oldTasks) {
            TaskNode newTask = TaskNode.builder()
                    .name(oldTask.getName()).workflowVersion(newVersion)
                    .timeoutSeconds(oldTask.getTimeoutSeconds()).maxRetries(oldTask.getMaxRetries())
                    .joinCondition(oldTask.getJoinCondition())
                    .build();

            newTask = taskNodeRepository.save(newTask);
            taskMapping.put(oldTask.getId(), newTask);
        }

        List<Dependency> oldDependencies = dependencyRepository.findByParent_WorkflowVersion_Id(latestVersion.getId());

        for (Dependency oldDependency : oldDependencies) {
            Dependency newDependency = Dependency.builder()
                    .parent(taskMapping.get(oldDependency.getParent().getId())).child(taskMapping.get(oldDependency.getChild().getId()))
                    .condition(oldDependency.getCondition()).expression(oldDependency.getExpression())
                    .build();
            dependencyRepository.save(newDependency);
        }
        return newVersion;
    }

    public List<WorkflowVersion> getVersions(Long workflowId) {
        return workflowVersionRepository.findByWorkflow_IdOrderByVersionNumberDesc(workflowId);
    }

    public WorkflowVersion getLatestPublishedVersion(Long workflowId) {
        return workflowVersionRepository.findByWorkflow_IdAndLatestTrue(workflowId).orElseThrow(() -> new RuntimeException("No published version found"));
    }

    @Transactional
    public WorkflowVersion publishVersion(Long versionId) {
        WorkflowVersion version = workflowVersionRepository.findById(versionId).orElseThrow(() -> new RuntimeException("Version not found"));
        List<WorkflowVersion> versions = workflowVersionRepository.findByWorkflow_IdOrderByVersionNumberDesc(version.getWorkflow().getId());

        for (WorkflowVersion v : versions) {
            v.setPublished(false);
            v.setLatest(false);

            workflowVersionRepository.save(v);
        }

        version.setPublished(true);
        version.setLatest(true);
        version.setPublishedAt(LocalDateTime.now());

        return workflowVersionRepository.save(version);
    }

    public List<WorkflowVersionResponse> getAllVersions(){
        return workflowVersionRepository.findAll()
                .stream()
                .map(version -> new WorkflowVersionResponse(

                        version.getId(),

                        version.getWorkflow().getId(),

                        version.getVersionNumber(),

                        version.getLatest(),

                        version.getPublished(),

                        version.getCreatedAt(),

                        version.getPublishedAt()

                ))
                .toList();
    }

    @Transactional
    public WorkflowVersion rollbackToVersion(Long workflowId, Long versionId) {
        WorkflowVersion targetVersion = workflowVersionRepository.findById(versionId).orElseThrow(() -> new RuntimeException("Version not found"));

        if (!targetVersion.getWorkflow().getId().equals(workflowId)) {
            throw new IllegalArgumentException("Version does not belong to workflow");
        }

        List<WorkflowVersion> versions = workflowVersionRepository.findByWorkflow_IdOrderByVersionNumberDesc(workflowId);

        for (WorkflowVersion version : versions) {
            version.setPublished(false);
            version.setLatest(false);

            workflowVersionRepository.save(version);
        }

        targetVersion.setPublished(true);
        targetVersion.setLatest(true);
        targetVersion.setPublishedAt(LocalDateTime.now());

        return workflowVersionRepository.save(targetVersion);
    }

    public WorkflowVersionDiffResponse compareVersions(
            Long version1Id,
            Long version2Id) {

        List<TaskNode> version1Tasks =
                taskNodeRepository.findByWorkflowVersion_Id(version1Id);

        List<TaskNode> version2Tasks =
                taskNodeRepository.findByWorkflowVersion_Id(version2Id);

        Map<String, TaskNode> oldTasks =
                version1Tasks.stream()
                        .collect(Collectors.toMap(
                                TaskNode::getName,
                                task -> task));

        Map<String, TaskNode> newTasks =
                version2Tasks.stream()
                        .collect(Collectors.toMap(
                                TaskNode::getName,
                                task -> task));

        List<String> addedTasks = new ArrayList<>();
        List<String> removedTasks = new ArrayList<>();
        List<TaskDifference> modifiedTasks = new ArrayList<>();


        for (String taskName : newTasks.keySet()) {
            if (!oldTasks.containsKey(taskName)) {
                addedTasks.add(taskName);
            }
        }


        for (String taskName : oldTasks.keySet()) {
            if (!newTasks.containsKey(taskName)) {
                removedTasks.add(taskName);
            }
        }

        // Modified Tasks
        for (String taskName : oldTasks.keySet()) {

            if (!newTasks.containsKey(taskName)) {
                continue;
            }

            TaskNode oldTask = oldTasks.get(taskName);
            TaskNode newTask = newTasks.get(taskName);

            List<String> changes = new ArrayList<>();

            if (!Objects.equals(
                    oldTask.getTimeoutSeconds(),
                    newTask.getTimeoutSeconds())) {

                changes.add(
                        "timeoutSeconds: "
                                + oldTask.getTimeoutSeconds()
                                + " -> "
                                + newTask.getTimeoutSeconds());
            }

            if (!Objects.equals(
                    oldTask.getMaxRetries(),
                    newTask.getMaxRetries())) {

                changes.add(
                        "maxRetries: "
                                + oldTask.getMaxRetries()
                                + " -> "
                                + newTask.getMaxRetries());
            }

            if (oldTask.getJoinCondition() != newTask.getJoinCondition()) {

                changes.add(
                        "joinCondition: "
                                + oldTask.getJoinCondition()
                                + " -> "
                                + newTask.getJoinCondition());
            }

            if (!changes.isEmpty()) {
                modifiedTasks.add(
                        TaskDifference.builder()
                                .taskName(taskName)
                                .changes(changes)
                                .build()
                );
            }
        }



        List<Dependency> version1Dependencies =
                dependencyRepository.findByParent_WorkflowVersion_Id(version1Id);

        List<Dependency> version2Dependencies =
                dependencyRepository.findByParent_WorkflowVersion_Id(version2Id);

        Set<String> oldDependencySet =
                version1Dependencies.stream()
                        .map(dep ->
                                dep.getParent().getName()
                                        + " -> "
                                        + dep.getChild().getName()
                                        + " ("
                                        + dep.getCondition()
                                        + ")")
                        .collect(Collectors.toSet());

        Set<String> newDependencySet =
                version2Dependencies.stream()
                        .map(dep ->
                                dep.getParent().getName()
                                        + " -> "
                                        + dep.getChild().getName()
                                        + " ("
                                        + dep.getCondition()
                                        + ")")
                        .collect(Collectors.toSet());

        List<String> addedDependencies =
                newDependencySet.stream()
                        .filter(dep -> !oldDependencySet.contains(dep))
                        .toList();

        List<String> removedDependencies =
                oldDependencySet.stream()
                        .filter(dep -> !newDependencySet.contains(dep))
                        .toList();

        return WorkflowVersionDiffResponse.builder()
                .addedTasks(addedTasks)
                .removedTasks(removedTasks)
                .modifiedTasks(modifiedTasks)
                .addedDependencies(addedDependencies)
                .removedDependencies(removedDependencies)
                .build();
    }
}
