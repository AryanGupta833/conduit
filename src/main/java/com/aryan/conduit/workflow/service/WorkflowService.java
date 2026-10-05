package com.aryan.conduit.workflow.service;

import com.aryan.conduit.workflow.dto.WorkflowResponse;
import com.aryan.conduit.workflow.dto.WorkflowVersionResponse;
import com.aryan.conduit.workflow.entity.*;
import com.aryan.conduit.workflow.repository.DependencyRepository;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public Workflow createWorkflow(String name,String cronExpression){
        if (cronExpression != null && !cronExpression.isBlank()) {
            CronExpression.parse(cronExpression);
        }
        Workflow workflow=Workflow.builder()
                .name(name).status(WorkflowStatus.DRAFT)
                .createdAt(LocalDateTime.now()).cronExpression(cronExpression)
                .active(true).priority(0)
                .build();

        workflow=workflowRepository.save(workflow);
        WorkflowVersion version=WorkflowVersion.builder().
        workflow(workflow).versionNumber(1)
                        .published(false)
                                .latest(true).createdAt(LocalDateTime.now()).
                build();

        workflowVersionRepository.save(version);
        return workflow;
    }

    public List<WorkflowResponse> getAllWorkflows() {
        return workflowRepository.findAll().stream().map(this::toResponse).toList();
    }

    public List<WorkflowResponse> getWorkflowsOwnedBy(Long ownerId) {
        return workflowRepository.findByOwner_IdOrderByIdDesc(ownerId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public void deleteWorkflow(Long workflowId) {
        Workflow workflow = workflowRepository.findByIdForUpdate(workflowId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Workflow not found"));
        long activeExecutions = jdbcTemplate.queryForObject("""
                select count(*) from workflow_executions
                where (workflow_id = ? or workflow_version_id in
                    (select id from workflow_versions where workflow_id = ?))
                  and status in ('PENDING','RUNNING','PAUSED','WAITING_FOR_HUMAN')
                """, Long.class, workflowId, workflowId);
        Boolean hasActiveTasks = jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from task_executions te
                    left join task_nodes tn on tn.id = te.task_node_id
                    left join workflow_versions wv on wv.id = tn.workflow_version_id
                    where (tn.workflow_id = ? or wv.workflow_id = ?)
                      and te.status in ('RUNNING','RETRYING','QUEUED','WAITING_FOR_HUMAN')
                )
                """, Boolean.class, workflowId, workflowId);
        if (activeExecutions > 0 || Boolean.TRUE.equals(hasActiveTasks)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "This workflow has active executions. Wait for them to finish or cancel them before deleting it.");
        }

        // This is an explicit, confirmed hard delete. Remove execution-linked rows
        // in FK order, then the graph; the parent workflow lock serializes this
        // with execution creation so a new run cannot race the cleanup.
        String taskNodeIds = "select id from task_nodes where workflow_id=? or workflow_version_id in " +
                "(select id from workflow_versions where workflow_id=?)";
        String workflowExecutionIds = "select id from workflow_executions where workflow_id=? or workflow_version_id in " +
                "(select id from workflow_versions where workflow_id=?)";
        jdbcTemplate.update("delete from execution_logs where task_exxecution_id in " +
                "(select id from task_executions where task_node_id in (" + taskNodeIds + ") " +
                "or workflow_execution_id in (" + workflowExecutionIds + "))", workflowId, workflowId, workflowId, workflowId);
        jdbcTemplate.update("delete from human_task_requests where workflow_execution_id in (" + workflowExecutionIds + ")", workflowId, workflowId);
        jdbcTemplate.update("delete from execution_contexts where workflow_execution_id in (" + workflowExecutionIds + ")", workflowId, workflowId);
        jdbcTemplate.update("delete from execution_events where workflow_execution_id in (" + workflowExecutionIds + ")", workflowId, workflowId);
        jdbcTemplate.update("delete from execution_notifications where workflow_execution_id in (" + workflowExecutionIds + ")", workflowId, workflowId);
        jdbcTemplate.update("delete from workflow_trigger_inbox where workflow_id=?", workflowId);
        jdbcTemplate.update("delete from outbox_events where aggregate_id in " +
                "(select id from task_executions where task_node_id in (" + taskNodeIds + ") " +
                "or workflow_execution_id in (" + workflowExecutionIds + "))", workflowId, workflowId, workflowId, workflowId);
        jdbcTemplate.update("delete from task_executions where task_node_id in (" + taskNodeIds + ") " +
                "or workflow_execution_id in (" + workflowExecutionIds + ")", workflowId, workflowId, workflowId, workflowId);
        jdbcTemplate.update("delete from workflow_executions where workflow_id=? or workflow_version_id in " +
                "(select id from workflow_versions where workflow_id=?)", workflowId, workflowId);
        jdbcTemplate.update("delete from dependencies where parent_task_id in (" + taskNodeIds + ") " +
                "or child_task_id in (" + taskNodeIds + ")", workflowId, workflowId, workflowId, workflowId);
        jdbcTemplate.update("delete from task_nodes where workflow_id=? or workflow_version_id in " +
                "(select id from workflow_versions where workflow_id=?)", workflowId, workflowId);
        jdbcTemplate.update("delete from workflow_versions where workflow_id=?", workflowId);
        // Workflow credentials and environment-profile rows are removed by the
        // schema's ON DELETE CASCADE constraints with the parent workflow.
        workflowRepository.delete(workflow);
    }

    private WorkflowResponse toResponse(Workflow workflow) {

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
                .findByIdForUpdate(workflowId)
                .orElseThrow(() ->
                        new RuntimeException("Workflow not found"));

        WorkflowVersion latestVersion =
                workflowVersionRepository
                        .findByWorkflow_IdAndLatestTrue(workflowId)
                        .or(() -> workflowVersionRepository.findTopByWorkflow_IdOrderByVersionNumberDesc(workflowId))
                        .orElseThrow(() ->
                                new RuntimeException("Latest version not found"));

        // Repeated clicks/retries should reuse the active draft rather than
        // creating a chain of empty versions. The workflow row is locked above,
        // so concurrent requests observe the same draft decision.
        if (!Boolean.TRUE.equals(latestVersion.getPublished())) {
            return new WorkflowVersionResponse(
                    latestVersion.getId(), workflow.getId(), latestVersion.getVersionNumber(),
                    latestVersion.getLatest(), latestVersion.getPublished(), latestVersion.getCreatedAt(),
                    latestVersion.getPublishedAt());
        }

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
