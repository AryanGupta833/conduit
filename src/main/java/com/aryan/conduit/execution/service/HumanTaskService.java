package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.dto.HumanTaskResponse;
import com.aryan.conduit.execution.entity.*;
import com.aryan.conduit.execution.repository.*;
import com.aryan.conduit.plugin.HumanTaskPlugin;
import com.aryan.conduit.execution.retry.TaskAlreadyInProgressException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class HumanTaskService {
    private final HumanTaskRequestRepository requests;
    private final TaskExecutionRepository tasks;
    private final WorkflowExecutionRepository workflows;
    private final ExecutionContextService context;
    private final DistributedWorkflowCoordinator coordinator;
    private final ObjectMapper mapper;
    private final com.aryan.conduit.observability.ConduitMetrics metrics;
    private final ExecutionLogService executionLogs;
    private ExecutionNotificationService notifications;

    public HumanTaskService(HumanTaskRequestRepository requests, TaskExecutionRepository tasks, WorkflowExecutionRepository workflows,
                            ExecutionContextService context, DistributedWorkflowCoordinator coordinator, ObjectMapper mapper,
                            com.aryan.conduit.observability.ConduitMetrics metrics, ExecutionLogService executionLogs) {
        this.requests = requests; this.tasks = tasks; this.workflows = workflows; this.context = context; this.coordinator = coordinator; this.mapper = mapper; this.metrics = metrics; this.executionLogs = executionLogs;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotifications(ExecutionNotificationService notifications) { this.notifications = notifications; }

    @Transactional
    public HumanTaskResponse createForTask(Long taskExecutionId) {
        TaskExecution task = tasks.findById(taskExecutionId).orElseThrow(() -> new IllegalArgumentException("Task execution not found"));
        Long workflowId = task.getWorkflowExecution().getId();
        WorkflowExecution workflow = workflows.findByIdForUpdate(workflowId).orElseThrow(() -> new IllegalArgumentException("Workflow execution not found"));
        task = tasks.findById(taskExecutionId).orElseThrow();
        var existing = requests.findByTaskExecution_Id(taskExecutionId);
        if (existing.isPresent()) return dto(existing.get());
        if (!"HUMAN_TASK".equalsIgnoreCase(task.getTaskNode().getPluginType())
                || (task.getStatus() != TaskExecutionStatus.QUEUED && task.getStatus() != TaskExecutionStatus.RUNNING))
            throw new IllegalStateException("Task is not an active HUMAN_TASK");
        if (workflow.getStatus() == WorkflowExecutionStatus.PAUSED
                || workflow.getStatus() == WorkflowExecutionStatus.WAITING_FOR_HUMAN) {
            throw new TaskAlreadyInProgressException("Workflow is paused or waiting for another human task");
        }
        if (workflow.getStatus() != WorkflowExecutionStatus.RUNNING) {
            // A queued delivery can arrive after workflow cancellation/finalization.
            // Mark it skipped so TaskWorker can ACK it instead of redelivering forever.
            task.setStatus(TaskExecutionStatus.SKIPPED);
            tasks.save(task);
            executionLogs.log(taskExecutionId, LogLevel.INFO,
                    "Skipping HUMAN_TASK because workflow is already " + workflow.getStatus());
            return null;
        }
        Map<String,Object> config = parseConfig(task.getTaskNode().getConfigurationJson());
        validate(config);
        LocalDateTime now = LocalDateTime.now();
        Integer timeout = number(config.get("timeoutSeconds"));
        LocalDateTime expires = timeout == null ? null : now.plusSeconds(timeout);
        Integer reminderAfter = number(config.get("reminderAfterSeconds"));
        Integer escalationAfter = number(config.get("escalationAfterSeconds"));
        HumanTaskRequest request = HumanTaskRequest.builder().taskExecution(task).workflowExecution(task.getWorkflowExecution())
                .status(HumanTaskStatus.WAITING_FOR_HUMAN).type(string(config.getOrDefault("type", "APPROVAL"), "APPROVAL").toUpperCase(Locale.ROOT))
                .title(string(config.getOrDefault("title", task.getTaskNode().getDisplayName() == null ? task.getTaskNode().getName() : task.getTaskNode().getDisplayName()), "Human intervention required"))
                .description(string(config.get("description"), null)).assignee(string(config.get("assignee"), null))
                .requestPayload(write(config.getOrDefault("metadata", Map.of()))).createdAt(now).updatedAt(now).expiresAt(expires)
                .createdBy("system").timeoutPolicy(string(config.getOrDefault("timeoutPolicy", "FAIL"), "FAIL").toUpperCase(Locale.ROOT))
                .reminderAt(reminderAfter == null ? null : now.plusSeconds(reminderAfter))
                .escalationAt(escalationAfter == null ? null : now.plusSeconds(escalationAfter))
                .escalationAssignee(string(config.get("escalationAssignee"), null)).build();
        request = requests.save(request);
        if (notifications != null) notifications.publish("human-task:" + request.getId(), "HUMAN_TASK_WAITING",
                "Human response required", request.getTitle(), workflowId);
        metrics.humanTaskCreated();
        executionLogs.log(taskExecutionId, LogLevel.INFO, "HUMAN_TASK_CREATED id=" + request.getId() + " type=" + request.getType());
        task.setStatus(TaskExecutionStatus.WAITING_FOR_HUMAN); tasks.save(task);
        workflow.setStatus(WorkflowExecutionStatus.WAITING_FOR_HUMAN); workflows.save(workflow);
        return dto(request);
    }

    @Transactional(readOnly = true)
    public List<HumanTaskResponse> list(String status, Long workflowExecutionId, String assignee, String type) {
        HumanTaskStatus parsedStatus = status == null || status.isBlank() ? null : parseStatus(status);
        String normalizedAssignee = assignee == null || assignee.isBlank() ? null : assignee.trim();
        String normalizedType = type == null || type.isBlank() ? null : type.trim();
        List<HumanTaskRequest> values;
        if (workflowExecutionId != null && normalizedAssignee != null && normalizedType != null && parsedStatus != null)
            values = requests.findByWorkflowExecution_IdAndAssigneeIgnoreCaseAndTypeIgnoreCaseAndStatusOrderByCreatedAtDesc(workflowExecutionId, normalizedAssignee, normalizedType, parsedStatus);
        else if (workflowExecutionId != null && normalizedAssignee != null && normalizedType != null)
            values = requests.findByWorkflowExecution_IdAndAssigneeIgnoreCaseAndTypeIgnoreCaseOrderByCreatedAtDesc(workflowExecutionId, normalizedAssignee, normalizedType);
        else if (workflowExecutionId != null && normalizedAssignee != null && parsedStatus != null)
            values = requests.findByWorkflowExecution_IdAndAssigneeIgnoreCaseAndStatusOrderByCreatedAtDesc(workflowExecutionId, normalizedAssignee, parsedStatus);
        else if (workflowExecutionId != null && normalizedType != null && parsedStatus != null)
            values = requests.findByWorkflowExecution_IdAndTypeIgnoreCaseAndStatusOrderByCreatedAtDesc(workflowExecutionId, normalizedType, parsedStatus);
        else if (normalizedAssignee != null && normalizedType != null && parsedStatus != null)
            values = requests.findByAssigneeIgnoreCaseAndTypeIgnoreCaseAndStatusOrderByCreatedAtDesc(normalizedAssignee, normalizedType, parsedStatus);
        else if (workflowExecutionId != null && parsedStatus != null)
            values = requests.findByWorkflowExecution_IdAndStatusOrderByCreatedAtDesc(workflowExecutionId, parsedStatus);
        else if (normalizedAssignee != null && parsedStatus != null)
            values = requests.findByAssigneeIgnoreCaseAndStatusOrderByCreatedAtDesc(normalizedAssignee, parsedStatus);
        else if (normalizedType != null && parsedStatus != null)
            values = requests.findByTypeIgnoreCaseAndStatusOrderByCreatedAtDesc(normalizedType, parsedStatus);
        else if (workflowExecutionId != null && normalizedAssignee != null)
            values = requests.findByWorkflowExecution_IdAndAssigneeIgnoreCaseOrderByCreatedAtDesc(workflowExecutionId, normalizedAssignee);
        else if (workflowExecutionId != null && normalizedType != null)
            values = requests.findByWorkflowExecution_IdAndTypeIgnoreCaseOrderByCreatedAtDesc(workflowExecutionId, normalizedType);
        else if (normalizedAssignee != null && normalizedType != null)
            values = requests.findByAssigneeIgnoreCaseAndTypeIgnoreCaseOrderByCreatedAtDesc(normalizedAssignee, normalizedType);
        else if (workflowExecutionId != null)
            values = requests.findByWorkflowExecution_IdOrderByCreatedAtDesc(workflowExecutionId);
        else if (normalizedAssignee != null)
            values = requests.findByAssigneeIgnoreCaseOrderByCreatedAtDesc(normalizedAssignee);
        else if (normalizedType != null)
            values = requests.findByTypeIgnoreCaseOrderByCreatedAtDesc(normalizedType);
        else if (parsedStatus != null)
            values = requests.findByStatusOrderByCreatedAtDesc(parsedStatus);
        else
            values = requests.findAllByOrderByCreatedAtDesc();
        return values.stream().map(this::dto).toList();
    }

    @Transactional(readOnly = true)
    public HumanTaskResponse get(Long id) { return dto(require(id)); }

    @Transactional(readOnly = true)
    public HumanTaskResponse getAuthorized(Long id, Authentication principal) {
        HumanTaskRequest request = require(id);
        authorizeAssignee(request, principal);
        return dto(request);
    }

    @Transactional
    public HumanTaskResponse completeAuthorized(Long id, HumanTaskStatus target, String comment, JsonNode response, Authentication principal) {
        authorizeAssignee(require(id), principal);
        return complete(id, target, comment, response);
    }

    @Transactional
    public HumanTaskResponse complete(Long id, HumanTaskStatus target, String comment, JsonNode response) {
        HumanTaskRequest request = require(id);
        Long workflowId = request.getWorkflowExecution().getId();
        WorkflowExecution workflow = workflows.findByIdForUpdate(workflowId).orElseThrow(() -> new IllegalArgumentException("Workflow execution not found"));
        request = require(id);
        TaskExecution currentTask = tasks.findById(request.getTaskExecution().getId()).orElseThrow();
        if (request.getStatus() == HumanTaskStatus.CANCELLED || workflow.getStatus() == WorkflowExecutionStatus.CANCELLED)
            throw conflict("Workflow task is cancelled.");
        LocalDateTime now = LocalDateTime.now();
        if (request.getStatus() != HumanTaskStatus.WAITING_FOR_HUMAN) throw conflict("Human task already completed.");
        if (request.getExpiresAt() != null && !request.getExpiresAt().isAfter(now)) {
            expireOne(id, now);
            return dto(require(id));
        }
        if (target == HumanTaskStatus.RESPONDED && !"INPUT".equals(request.getType())) throw badRequest("This human task does not accept input responses");
        if (target == HumanTaskStatus.APPROVED && !Set.of("APPROVAL", "REVIEW", "CONFIRMATION").contains(request.getType())) throw badRequest("This human task does not accept approval");
        if (target == HumanTaskStatus.REJECTED && !Set.of("APPROVAL", "REVIEW", "CONFIRMATION").contains(request.getType())) throw badRequest("This human task does not accept rejection");
        Map<String,Object> payload = new LinkedHashMap<>();
        if (comment != null && !comment.isBlank()) { if (comment.length() > 10_000) throw badRequest("comment is too long"); payload.put("comment", comment); }
        Map<String,Object> safeVariables = Map.of();
        if (target == HumanTaskStatus.RESPONDED) {
            if (response == null || !response.isObject()) throw badRequest("response must be a JSON object");
            if (response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_536) throw badRequest("response exceeds 64 KiB");
            safeVariables = sanitize(response);
            payload.put("response", safeVariables);
        } else if (response != null && !response.isNull()) throw badRequest("response is only valid for INPUT tasks");
        String json = write(payload);
        String actor = currentActor();
        if (requests.complete(id, HumanTaskStatus.WAITING_FOR_HUMAN, target, json, actor, now) != 1) throw conflict("Human task already completed.");
        request = require(id);
        metrics.humanTaskCompleted(target.name());
        TaskExecution task = tasks.findById(request.getTaskExecution().getId()).orElseThrow();
        boolean success = target == HumanTaskStatus.APPROVED || target == HumanTaskStatus.RESPONDED;
        task.setStatus(success ? TaskExecutionStatus.SUCCESS : TaskExecutionStatus.FAILED);
        tasks.save(task);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("output", target == HumanTaskStatus.RESPONDED ? "Human input submitted" : target == HumanTaskStatus.APPROVED ? "Approved" : "Rejected");
        result.put("variables", success ? safeVariables : Map.of());
        result.put("metadata", Map.of("humanTaskId", id, "decision", target.name()));
        context.commitTaskResult(workflowId, task.getTaskNode().getId(), task.getTaskNode().getName(), success ? "SUCCESS" : "FAILED", result);
        coordinator.resumeAfterHumanTask(workflowId, task.getTaskNode().getId());
        executionLogs.log(task.getId(), LogLevel.INFO, "HUMAN_TASK_" + target.name() + " id=" + id + " actor=" + actor);
        return dto(request);
    }

    @Scheduled(fixedDelayString = "${conduit.human-tasks.expiration-poll-ms:60000}")
    public void expireDue() {
        LocalDateTime now = LocalDateTime.now();
        for (Long id : requests.findExpiredIds(HumanTaskStatus.WAITING_FOR_HUMAN, now)) {
            try { expireOne(id, now); } catch (RuntimeException ignored) { /* another instance may have won */ }
        }
    }

    @Scheduled(fixedDelayString = "${conduit.human-tasks.reminder-poll-ms:60000}")
    public void processRemindersAndEscalations() {
        LocalDateTime now = LocalDateTime.now();
        for (Long id : requests.findReminderDueIds(HumanTaskStatus.WAITING_FOR_HUMAN, now)) {
            try {
                HumanTaskRequest task = require(id);
                if (requests.markReminderSent(id, HumanTaskStatus.WAITING_FOR_HUMAN, now) == 1 && notifications != null)
                    notifications.publish("human-task-reminder:" + id, "HUMAN_TASK_REMINDER", "Reminder: " + task.getTitle(), "A human task is still awaiting a response", task.getWorkflowExecution().getId());
            } catch (RuntimeException ignored) { }
        }
        for (Long id : requests.findEscalationDueIds(HumanTaskStatus.WAITING_FOR_HUMAN, now)) {
            try {
                HumanTaskRequest task = require(id);
                if (requests.escalate(id, HumanTaskStatus.WAITING_FOR_HUMAN, now) == 1 && notifications != null)
                    notifications.publish("human-task-escalation:" + id, "HUMAN_TASK_ESCALATED", "Escalated: " + task.getTitle(), "Human task reassigned for escalation", task.getWorkflowExecution().getId());
            } catch (RuntimeException ignored) { }
        }
    }

    @Transactional
    public void expireOne(Long id, LocalDateTime now) {
        HumanTaskRequest request = require(id);
        if (requests.expire(id, HumanTaskStatus.WAITING_FOR_HUMAN, HumanTaskStatus.EXPIRED, "{}", now) == 0) return;
        metrics.humanTaskExpired();
        TaskExecution task = tasks.findById(request.getTaskExecution().getId()).orElseThrow();
        String policy = request.getTimeoutPolicy();
        task.setStatus("SKIP".equals(policy) ? TaskExecutionStatus.SKIPPED : "REJECT".equals(policy) ? TaskExecutionStatus.FAILED : TaskExecutionStatus.TIMEOUT);
        tasks.save(task);
        String taskStatus = task.getStatus().name();
        context.commitTaskResult(request.getWorkflowExecution().getId(), task.getTaskNode().getId(), task.getTaskNode().getName(), taskStatus,
                Map.of("output", "Human task expired", "variables", Map.of(), "metadata", Map.of("humanTaskId", id, "decision", "EXPIRED")));
        coordinator.resumeAfterHumanTask(request.getWorkflowExecution().getId(), task.getTaskNode().getId());
        executionLogs.log(task.getId(), LogLevel.WARN, "HUMAN_TASK_EXPIRED id=" + id);
    }

    private HumanTaskRequest require(Long id) { return requests.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Human task does not exist")); }
    private void authorizeAssignee(HumanTaskRequest request, Authentication principal) {
        boolean admin = principal.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!admin && (request.getAssignee() == null || !request.getAssignee().equalsIgnoreCase(principal.getName())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This human task is not assigned to your account");
    }
    private HumanTaskResponse dto(HumanTaskRequest h) {
        Object response = null;
        try { if (h.getResponsePayload() != null) response = mapper.readTree(h.getResponsePayload()); } catch (Exception ignored) { }
        return new HumanTaskResponse(h.getId(), h.getWorkflowExecution().getId(), h.getTaskExecution().getId(), h.getStatus(), h.getType(), h.getTitle(), h.getDescription(), h.getAssignee(), h.getCreatedBy(), h.getCompletedBy(), h.getCreatedAt(), h.getExpiresAt(), h.getCompletedAt(), response);
    }
    private Map<String,Object> parseConfig(String json) { try { return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>(){}); } catch (Exception e) { throw badRequest("Invalid HUMAN_TASK configuration JSON"); } }
    private void validate(Map<String,Object> config) { new HumanTaskPlugin().validateConfiguration(config); if (config.get("description") instanceof String s && s.length() > 10_000) throw badRequest("description is too long"); if (config.get("assignee") instanceof String s && s.length() > 200) throw badRequest("assignee is too long"); }
    private Map<String,Object> sanitize(JsonNode node) {
        Map<String,Object> result = new LinkedHashMap<>(); node.fields().forEachRemaining(e -> {
            if (e.getKey().matches("(?i).*(password|secret|token|credential|private.?key|access.?key|api.?key|authorization|cookie).*")) throw badRequest("Sensitive response fields are not allowed");
            JsonNode value = e.getValue();
            result.put(e.getKey(), sanitizeValue(value, 0));
        }); return result;
    }
    private Object sanitizeValue(JsonNode value, int depth) {
        if (depth > 32) throw badRequest("response nesting exceeds 32 levels");
        if (value.isObject()) {
            Map<String, Object> object = new LinkedHashMap<>();
            value.fields().forEachRemaining(entry -> {
                if (entry.getKey().matches("(?i).*(password|secret|token|credential|private.?key|access.?key|api.?key|authorization|cookie).*"))
                    throw badRequest("Sensitive response fields are not allowed");
                object.put(entry.getKey(), sanitizeValue(entry.getValue(), depth + 1));
            });
            return object;
        }
        if (value.isArray()) { List<Object> list = new ArrayList<>(); value.forEach(item -> list.add(sanitizeValue(item, depth + 1))); return list; }
        return mapper.convertValue(value, Object.class);
    }
    private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw badRequest("Value cannot be serialized as JSON"); } }
    private String currentActor() { Authentication auth = SecurityContextHolder.getContext().getAuthentication(); return auth == null || !auth.isAuthenticated() ? "anonymous" : auth.getName(); }
    private String string(Object value, String fallback) { return value instanceof String s && !s.isBlank() ? s : fallback; }
    private Integer number(Object value) { return value instanceof Number n ? n.intValue() : null; }
    private HumanTaskStatus parseStatus(String value) { try { return HumanTaskStatus.valueOf(value.toUpperCase(Locale.ROOT)); } catch (Exception e) { throw badRequest("Invalid human task status"); } }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    @jakarta.annotation.PostConstruct
    void bindWaitingMetric() { metrics.setWaitingHumanTaskSupplier(() -> requests.countByStatus(HumanTaskStatus.WAITING_FOR_HUMAN)); }
}
