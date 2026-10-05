package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.dto.ExecutionEventResponse;
import com.aryan.conduit.execution.repository.ExecutionEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ExecutionHistoryService {
    private final ExecutionEventRepository events;

    public List<ExecutionEventResponse> history(Long executionId) {
        return events.findByWorkflowExecution_IdOrderByOccurredAtAscIdAsc(executionId).stream()
                .map(event -> new ExecutionEventResponse(event.getId(), event.getTaskExecutionId(), event.getType(),
                        event.getStatus(), event.getOccurredAt(), event.getDetail()))
                .toList();
    }
}
