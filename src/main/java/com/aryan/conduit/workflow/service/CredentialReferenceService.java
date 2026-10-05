package com.aryan.conduit.workflow.service;

import com.aryan.conduit.workflow.dto.WorkflowCredentialRequest;
import com.aryan.conduit.workflow.dto.WorkflowCredentialResponse;
import com.aryan.conduit.workflow.entity.WorkflowCredential;
import com.aryan.conduit.workflow.repository.WorkflowCredentialRepository;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** Workflow-scoped secret storage and just-in-time plugin configuration resolution. */
@Service
@RequiredArgsConstructor
public class CredentialReferenceService {
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,119}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final WorkflowCredentialRepository credentials;
    private final WorkflowRepository workflows;
    private final Environment environment;
    private final com.aryan.conduit.security.service.SecurityAuditService audit;

    @Transactional(readOnly = true)
    public List<WorkflowCredentialResponse> list(Long workflowId) {
        requireWorkflow(workflowId);
        return credentials.findByWorkflow_IdOrderByNameAsc(workflowId).stream().map(this::response).toList();
    }

    @Transactional
    public WorkflowCredentialResponse create(Long workflowId, WorkflowCredentialRequest request) {
        return create(workflowId, request, "unknown", null);
    }

    @Transactional
    public WorkflowCredentialResponse create(Long workflowId, WorkflowCredentialRequest request, String actor, String ip) {
        var workflow = requireWorkflow(workflowId);
        String name = validate(request);
        if (credentials.findByWorkflow_IdAndName(workflowId, name).isPresent()) throw new IllegalArgumentException("Credential name already exists");
        LocalDateTime now = LocalDateTime.now();
        var saved = credentials.save(WorkflowCredential.builder().workflow(workflow).name(name).secretCiphertext(encrypt(request.value())).createdAt(now).updatedAt(now).build());
        audit.record("CREDENTIAL_CREATED", actor, "workflow=" + workflowId + ";name=" + name, ip);
        return response(saved);
    }

    @Transactional
    public WorkflowCredentialResponse update(Long workflowId, Long credentialId, WorkflowCredentialRequest request) {
        return update(workflowId, credentialId, request, "unknown", null);
    }

    @Transactional
    public WorkflowCredentialResponse update(Long workflowId, Long credentialId, WorkflowCredentialRequest request, String actor, String ip) {
        String name = validate(request);
        var credential = credentials.findByIdAndWorkflow_Id(credentialId, workflowId).orElseThrow(() -> new IllegalArgumentException("Credential not found"));
        credentials.findByWorkflow_IdAndName(workflowId, name).filter(other -> !other.getId().equals(credentialId)).ifPresent(other -> { throw new IllegalArgumentException("Credential name already exists"); });
        credential.setName(name); credential.setSecretCiphertext(encrypt(request.value())); credential.setUpdatedAt(LocalDateTime.now());
        var saved = credentials.save(credential);
        audit.record("CREDENTIAL_ROTATED", actor, "workflow=" + workflowId + ";name=" + name, ip);
        return response(saved);
    }

    @Transactional
    public void delete(Long workflowId, Long credentialId) {
        delete(workflowId, credentialId, "unknown", null);
    }

    @Transactional
    public void delete(Long workflowId, Long credentialId, String actor, String ip) {
        var credential = credentials.findByIdAndWorkflow_Id(credentialId, workflowId).orElseThrow(() -> new IllegalArgumentException("Credential not found"));
        credentials.delete(credential);
        audit.record("CREDENTIAL_DELETED", actor, "workflow=" + workflowId + ";name=" + credential.getName(), ip);
    }

    /** Resolves only the exact object form {"$credential":"name"}; ordinary strings are never expanded. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveReferences(Long workflowId, Map<String, Object> configuration) {
        return resolveReferences(workflowId, configuration, "system", "unspecified");
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveReferences(Long workflowId, Map<String, Object> configuration, String actor, String taskName) {
        return (Map<String, Object>) resolveValue(workflowId, configuration, 0, actor, taskName);
    }

    @Transactional(readOnly = true)
    public com.aryan.conduit.plugin.sdk.PluginResult redactResult(Long workflowId, com.aryan.conduit.plugin.sdk.PluginResult result) {
        List<String> secrets = plaintextSecrets(workflowId);
        return new com.aryan.conduit.plugin.sdk.PluginResult(result.isSuccess(), redact(result.getOutput(), secrets),
                castMap(redactValue(result.getVariables(), secrets)), castMap(redactValue(result.getMetadata(), secrets)));
    }

    @Transactional(readOnly = true)
    public String redactMessage(Long workflowId, String message) {
        return redact(message, plaintextSecrets(workflowId));
    }

    private List<String> plaintextSecrets(Long workflowId) {
        return credentials.findByWorkflow_IdOrderByNameAsc(workflowId).stream()
                .map(WorkflowCredential::getSecretCiphertext).map(this::decrypt).filter(value -> !value.isEmpty()).toList();
    }

    private Object redactValue(Object value, List<String> secrets) {
        if (value instanceof String text) return redact(text, secrets);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> { if (key instanceof String name) copy.put(name, redactValue(child, secrets)); });
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(child -> redactValue(child, secrets)).toList();
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }

    private String redact(String text, List<String> secrets) {
        if (text == null) return null;
        String safe = text;
        for (String secret : secrets) safe = safe.replace(secret, "[REDACTED]");
        return safe;
    }

    private Object resolveValue(Long workflowId, Object value, int depth, String actor, String taskName) {
        if (depth > 32) throw new IllegalArgumentException("Credential references exceed maximum configuration nesting");
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("$credential")) {
                if (map.size() != 1 || !(map.get("$credential") instanceof String name) || !NAME.matcher(name).matches())
                    throw new IllegalArgumentException("Credential reference must be an object containing only a valid $credential name");
                var credential = credentials.findByWorkflow_IdAndName(workflowId, name).orElseThrow(() -> new IllegalArgumentException("Credential reference is not configured for this workflow: " + name));
                audit.record("CREDENTIAL_USED", actor, "workflow=" + workflowId + ";task=" + taskName + ";credential=" + name, null);
                return decrypt(credential.getSecretCiphertext());
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Plugin configuration keys must be strings");
                copy.put(key, resolveValue(workflowId, entry.getValue(), depth + 1, actor, taskName));
            }
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> resolveValue(workflowId, item, depth + 1, actor, taskName)).toList();
        return value;
    }

    private com.aryan.conduit.workflow.entity.Workflow requireWorkflow(Long workflowId) {
        return workflows.findById(workflowId).orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
    }

    private String validate(WorkflowCredentialRequest request) {
        if (request == null || request.name() == null || !NAME.matcher(request.name()).matches()) throw new IllegalArgumentException("Credential name must be 1-120 letters, numbers, dots, dashes, or underscores");
        if (request.value() == null || request.value().isBlank() || request.value().length() > 16_384) throw new IllegalArgumentException("Credential value is required and limited to 16 KiB");
        encryptionKey();
        return request.name();
    }

    private byte[] encryptionKey() {
        String configured = environment.getProperty("conduit.credentials.encryption-key", "");
        try {
            byte[] key = Base64.getDecoder().decode(configured);
            if (key.length != 32) throw new IllegalArgumentException();
            return key;
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Set CONDUIT_CREDENTIALS_ENCRYPTION_KEY to a base64-encoded 32-byte key before managing or using credentials");
        }
    }

    private String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey(), "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length); System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception error) { throw new IllegalStateException("Credential encryption failed", error); }
    }

    private String decrypt(String encoded) {
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length < 29) throw new IllegalArgumentException("Invalid encrypted credential");
            byte[] nonce = Arrays.copyOfRange(payload, 0, 12); byte[] ciphertext = Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey(), "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("Credential decryption failed; verify the configured encryption key", error); }
    }

    private WorkflowCredentialResponse response(WorkflowCredential credential) {
        return new WorkflowCredentialResponse(credential.getId(), credential.getName(), true, credential.getCreatedAt(), credential.getUpdatedAt());
    }
}
