package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.dto.ExecutionNotificationResponse;
import com.aryan.conduit.execution.entity.ExecutionNotification;
import com.aryan.conduit.execution.repository.ExecutionNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExecutionNotificationService {
    private final ExecutionNotificationRepository repository;

    @Transactional
    public void publish(String eventKey, String type, String title, String message, Long executionId) {
        if (repository.findByEventKey(eventKey).isPresent()) return;
        repository.save(ExecutionNotification.builder().eventKey(eventKey).type(type).title(title).message(message)
                .workflowExecutionId(executionId).createdAt(LocalDateTime.now()).build());
    }

    @Transactional(readOnly = true)
    public List<ExecutionNotificationResponse> list(boolean unreadOnly) {
        List<ExecutionNotification> values = unreadOnly ? repository.findTop200ByReadAtIsNullOrderByCreatedAtDesc() : repository.findTop200ByOrderByCreatedAtDesc();
        return values.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount() { return repository.countByReadAtIsNull(); }

    @Transactional
    public ExecutionNotificationResponse markRead(Long id) {
        ExecutionNotification item = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        if (item.getReadAt() == null) item.setReadAt(LocalDateTime.now());
        return toResponse(repository.save(item));
    }

    @Transactional
    public int markAllRead() {
        return repository.markAllRead(LocalDateTime.now());
    }

    private ExecutionNotificationResponse toResponse(ExecutionNotification item) {
        return new ExecutionNotificationResponse(item.getId(), item.getType(), item.getTitle(), item.getMessage(), item.getWorkflowExecutionId(), item.getCreatedAt(), item.getReadAt());
    }
}
