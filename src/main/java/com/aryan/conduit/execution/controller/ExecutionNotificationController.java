package com.aryan.conduit.execution.controller;

import com.aryan.conduit.execution.dto.ExecutionNotificationResponse;
import com.aryan.conduit.execution.service.ExecutionNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class ExecutionNotificationController {
    private final ExecutionNotificationService notifications;

    @GetMapping
    public List<ExecutionNotificationResponse> list(@RequestParam(defaultValue = "false") boolean unreadOnly) { return notifications.list(unreadOnly); }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() { return Map.of("count", notifications.unreadCount()); }

    @PostMapping("/{id}/read")
    public ExecutionNotificationResponse markRead(@PathVariable Long id) { return notifications.markRead(id); }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() { return Map.of("updated", notifications.markAllRead()); }
}
