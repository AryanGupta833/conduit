package com.aryan.conduit.execution.controller;

import com.aryan.conduit.execution.queue.QueueOperationsResponse;
import com.aryan.conduit.execution.queue.TaskQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations/queue")
@RequiredArgsConstructor
public class QueueOperationsController {
    private final TaskQueueService queue;

    @GetMapping
    public QueueOperationsResponse get() { return queue.operations(); }
}
