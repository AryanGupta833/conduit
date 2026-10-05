package com.aryan.conduit.workflow.service;

import com.aryan.conduit.workflow.dto.WorkflowEnvironmentProfileRequest;
import com.aryan.conduit.workflow.dto.WorkflowEnvironmentProfileResponse;
import com.aryan.conduit.workflow.entity.WorkflowEnvironmentProfile;
import com.aryan.conduit.workflow.repository.WorkflowEnvironmentProfileRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Named, non-secret variable sets snapshotted into a run's durable execution context. */
@Service
@RequiredArgsConstructor
public class WorkflowEnvironmentProfileService {
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(password|secret|token|credential|private.?key|access.?key|api.?key|authorization|cookie).*");
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");
    private final WorkflowEnvironmentProfileRepository profiles;
    private final WorkflowRepository workflows;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public List<WorkflowEnvironmentProfileResponse> list(Long workflowId) {
        var workflow = workflows.findById(workflowId).orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
        return profiles.findByWorkflow_IdOrderByNameAsc(workflowId).stream().map(profile -> dto(profile, profile.getId().equals(workflow.getEnvironmentProfileId()))).toList();
    }

    @Transactional
    public WorkflowEnvironmentProfileResponse create(Long workflowId, WorkflowEnvironmentProfileRequest request) {
        var workflow = workflows.findById(workflowId).orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
        validateName(request.name());
        String json = encode(request.values());
        LocalDateTime now = LocalDateTime.now();
        var saved = profiles.save(WorkflowEnvironmentProfile.builder().workflow(workflow).name(request.name().trim()).valuesJson(json).createdAt(now).updatedAt(now).build());
        return dto(saved, false);
    }

    @Transactional
    public WorkflowEnvironmentProfileResponse update(Long workflowId, Long profileId, WorkflowEnvironmentProfileRequest request) {
        var profile = profiles.findByIdAndWorkflow_Id(profileId, workflowId).orElseThrow(() -> new IllegalArgumentException("Environment profile not found"));
        validateName(request.name());
        profile.setName(request.name().trim()); profile.setValuesJson(encode(request.values())); profile.setUpdatedAt(LocalDateTime.now());
        var workflow = workflows.findById(workflowId).orElseThrow();
        return dto(profiles.save(profile), profileId.equals(workflow.getEnvironmentProfileId()));
    }

    @Transactional
    public void select(Long workflowId, Long profileId) {
        var workflow = workflows.findByIdForUpdate(workflowId).orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
        if (profileId != null) profiles.findByIdAndWorkflow_Id(profileId, workflowId).orElseThrow(() -> new IllegalArgumentException("Environment profile does not belong to workflow"));
        workflow.setEnvironmentProfileId(profileId);
        workflows.save(workflow);
    }

    @Transactional
    public void delete(Long workflowId, Long profileId) {
        var workflow = workflows.findByIdForUpdate(workflowId).orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
        var profile = profiles.findByIdAndWorkflow_Id(profileId, workflowId).orElseThrow(() -> new IllegalArgumentException("Environment profile not found"));
        if (profileId.equals(workflow.getEnvironmentProfileId())) workflow.setEnvironmentProfileId(null);
        profiles.delete(profile);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> valuesFor(Long profileId, Long workflowId) {
        if (profileId == null) return Map.of();
        var profile = profiles.findByIdAndWorkflow_Id(profileId, workflowId).orElseThrow(() -> new IllegalStateException("Selected environment profile is unavailable"));
        try { return mapper.readValue(profile.getValuesJson(), new TypeReference<>() { }); }
        catch (Exception error) { throw new IllegalStateException("Stored environment profile is invalid", error); }
    }

    private String encode(Map<String, Object> input) {
        Map<String, Object> values = input == null ? Map.of() : input;
        if (values.size() > 200) throw new IllegalArgumentException("An environment profile can contain at most 200 top-level values");
        Map<String, Object> safe = sanitize(values, 0);
        try {
            byte[] json = mapper.writeValueAsBytes(safe);
            if (json.length > 65_536) throw new IllegalArgumentException("Environment profile exceeds 64 KiB");
            return new String(json, java.nio.charset.StandardCharsets.UTF_8);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("Environment profile must contain JSON-compatible values", error); }
    }

    private Map<String, Object> sanitize(Map<String, Object> input, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Environment profile nesting exceeds 16 levels");
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (!KEY.matcher(key).matches() || SENSITIVE.matcher(key).matches() || key.equals("tasks") || key.equals("variables"))
                throw new IllegalArgumentException("Invalid or secret-like environment variable name: " + key);
            result.put(key, sanitizeValue(value, depth + 1));
        });
        return result;
    }

    private Object sanitizeValue(Object value, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Environment profile nesting exceeds 16 levels");
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> nested = new LinkedHashMap<>();
            source.forEach((key, child) -> {
                if (!(key instanceof String name) || !KEY.matcher(name).matches() || SENSITIVE.matcher(name).matches()) throw new IllegalArgumentException("Invalid or secret-like nested environment key");
                nested.put(name, sanitizeValue(child, depth + 1));
            });
            return nested;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 1000) throw new IllegalArgumentException("Environment profile lists are limited to 1000 values");
            return list.stream().map(child -> sanitizeValue(child, depth + 1)).toList();
        }
        if (value instanceof String text && text.length() > 8192) throw new IllegalArgumentException("Environment profile strings are limited to 8 KiB");
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Environment profiles accept only JSON values");
    }

    private void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Profile name is required and must be at most 120 characters");
    }

    private WorkflowEnvironmentProfileResponse dto(WorkflowEnvironmentProfile profile, boolean selected) {
        try { return new WorkflowEnvironmentProfileResponse(profile.getId(), profile.getName(), mapper.readValue(profile.getValuesJson(), new TypeReference<>() { }), selected, profile.getCreatedAt(), profile.getUpdatedAt()); }
        catch (Exception error) { throw new IllegalStateException("Stored environment profile is invalid", error); }
    }
}
