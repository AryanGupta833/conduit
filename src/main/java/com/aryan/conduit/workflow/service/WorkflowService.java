package com.aryan.conduit.workflow.service;

import com.aryan.conduit.workflow.dto.WorkflowResponse;
import com.aryan.conduit.workflow.dto.WorkflowVersionResponse;
import com.aryan.conduit.workflow.entity.*;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowService {
    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final DependencyRepository dependencyRepository;

    @Transactional
    public Workflow createWorkflow(String name,String cronExpression){
        Workflow workflow=Workflow.builder()
                .name(name).status(WorkflowStatus.DRAFT)
                .createdAt(LocalDateTime.now()).cronExpression(cronExpression)
                .active(true)
                .build();

        workflow=workflowRepository.save(workflow);
        WorkflowVersion version=WorkflowVersion.builder().
        workflow(workflow).versionNumber(1)
                        .published(true)
                                .latest(true).createdAt(LocalDateTime.now()).publishedAt(LocalDateTime.now()).
                build();

        workflowVersionRepository.save(version);
        return workflow;
    }

    public List<WorkflowResponse> getAllWorkflows() {

        return workflowRepository.findAll()
                .stream()
                .map(workflow -> {

                    Integer latestVersion =
                            workflowVersionRepository
                                    .findTopByWorkflow_IdOrderByVersionNumberDesc(workflow.getId())
                                    .map(WorkflowVersion::getVersionNumber)
                                    .orElse(0);

                    LocalDateTime nextScheduledRun = null;

                    if (workflow.getCronExpression() != null &&
                            !workflow.getCronExpression().isBlank()) {

                        CronExpression cron =
                                CronExpression.parse(workflow.getCronExpression());

                        LocalDateTime base =
                                workflow.getLastScheduledRun() == null
                                        ? workflow.getCreatedAt()
                                        : workflow.getLastScheduledRun();

                        nextScheduledRun = cron.next(base);
                    }

                    return new WorkflowResponse(
                            workflow.getId(),
                            workflow.getName(),
                            workflow.getStatus(),
                            workflow.getActive(),
                            workflow.getCronExpression(),
                            latestVersion,
                            workflow.getCreatedAt(),
                            workflow.getLastScheduledRun(),
                            nextScheduledRun
                    );
                })
                .toList();
    }

    @Transactional
    public WorkflowResponse updateWorkflowActive(Long workflowId,Boolean active){
        Workflow workflow=workflowRepository.findById(workflowId).orElseThrow(()->new RuntimeException("Workflow not found"));
        LocalDateTime nextScheduledRun = null;

        if (workflow.getCronExpression() != null &&
                !workflow.getCronExpression().isBlank()) {

            CronExpression cron =
                    CronExpression.parse(workflow.getCronExpression());

            LocalDateTime base =
                    workflow.getLastScheduledRun() == null
                            ? workflow.getCreatedAt()
                            : workflow.getLastScheduledRun();

            nextScheduledRun = cron.next(base);
        }
        workflow.setActive(active);
        workflowRepository.save(workflow);
        WorkflowVersion latest=workflowVersionRepository.findByWorkflow_IdAndLatestTrue(workflowId).orElse(null);
        return new WorkflowResponse(workflow.getId(),workflow.getName()
        ,workflow.getStatus(),workflow.getActive(),workflow.getCronExpression(),
                latest==null?0: latest.getVersionNumber(), workflow.getCreatedAt(),workflow.getLastScheduledRun(),nextScheduledRun);
    }

    @Transactional
    public WorkflowVersionResponse createNewVersion(Long workflowId) {
        Workflow workflow = workflowRepository
                .findById(workflowId)
                .orElseThrow(() ->
                        new RuntimeException("Workflow not found"));

        WorkflowVersion latestVersion =
                workflowVersionRepository
                        .findByWorkflow_IdAndLatestTrue(workflowId)
                        .orElseThrow(() ->
                                new RuntimeException("Latest version not found"));

        latestVersion.setLatest(false);

        workflowVersionRepository.save(latestVersion);

        WorkflowVersion newVersion =
                WorkflowVersion.builder()
                        .workflow(workflow)
                        .versionNumber(
                                latestVersion.getVersionNumber() + 1
                        )
                        .latest(true)
                        .published(false)
                        .createdAt(LocalDateTime.now())
                        .build();

        workflowVersionRepository.save(newVersion);

        List<TaskNode> oldTasks =
                taskNodeRepository.findByWorkflowVersion_Id(
                        latestVersion.getId()
                );

        Map<Long, TaskNode> taskMap = new HashMap<>();
        for(TaskNode oldTask : oldTasks){

            TaskNode newTask =
                    TaskNode.builder()
                            .workflowVersion(newVersion)
                            .name(oldTask.getName())
                            .displayName(oldTask.getDisplayName())
                            .pluginType(oldTask.getPluginType())
                            .configurationJson(oldTask.getConfigurationJson())
                            .timeoutSeconds(oldTask.getTimeoutSeconds())
                            .maxRetries(oldTask.getMaxRetries())
                            .joinCondition(oldTask.getJoinCondition())
                            .xPosition(oldTask.getXPosition())
                            .yPosition(oldTask.getYPosition())
                            .build();

            taskNodeRepository.save(newTask);

            taskMap.put(oldTask.getId(), newTask);
        }List<Dependency> oldDependencies =
                dependencyRepository
                        .findByParent_WorkflowVersion_Id(
                                latestVersion.getId()
                        );

        for(Dependency oldDependency : oldDependencies){

            Dependency dependency =
                    Dependency.builder()
                            .parent(
                                    taskMap.get(
                                            oldDependency.getParent().getId()
                                    )
                            )
                            .child(
                                    taskMap.get(
                                            oldDependency.getChild().getId()
                                    )
                            )
                            .condition(oldDependency.getCondition())
                            .expression(oldDependency.getExpression())
                            .build();

            dependencyRepository.save(dependency);
        }
        return new WorkflowVersionResponse(

                newVersion.getId(),
                workflow.getId(),

                newVersion.getVersionNumber(),

                newVersion.getLatest(),

                newVersion.getPublished(),

                newVersion.getCreatedAt(),
                newVersion.getPublishedAt()

        );
    }



}
