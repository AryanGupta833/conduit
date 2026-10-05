package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.dto.ExecutionComparisonResponse;
import com.aryan.conduit.execution.repository.TaskExecutionRepository;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class ExecutionComparisonService {
    private final WorkflowExecutionRepository executions;
    private final TaskExecutionRepository tasks;
    private final TaskOutputService outputs;

    @Transactional(readOnly = true)
    public ExecutionComparisonResponse compare(Long firstId, Long secondId) {
        if (!executions.existsById(firstId) || !executions.existsById(secondId)) throw new IllegalArgumentException("Execution not found");
        Map<String, com.aryan.conduit.execution.entity.TaskExecution> first = byName(firstId);
        Map<String, com.aryan.conduit.execution.entity.TaskExecution> second = byName(secondId);
        var names = new java.util.TreeSet<String>(); names.addAll(first.keySet()); names.addAll(second.keySet());
        var changes = names.stream().map(name -> {
            var a = first.get(name); var b = second.get(name);
            Object resultA = a == null ? null : outputs.getOutput(firstId, a.getTaskNode().getId());
            Object resultB = b == null ? null : outputs.getOutput(secondId, b.getTaskNode().getId());
            return new ExecutionComparisonResponse.TaskComparison(name,
                    a == null ? "NOT_PRESENT" : a.getStatus().name(), b == null ? "NOT_PRESENT" : b.getStatus().name(),
                    a == null ? null : a.getRetryCount(), b == null ? null : b.getRetryCount(), !java.util.Objects.equals(resultA, resultB));
        }).toList();
        return new ExecutionComparisonResponse(firstId, secondId, changes);
    }

    private Map<String, com.aryan.conduit.execution.entity.TaskExecution> byName(Long executionId) {
        Map<String, com.aryan.conduit.execution.entity.TaskExecution> values = new TreeMap<>();
        for (var task : tasks.findByWorkflowExecution_Id(executionId)) values.put(task.getTaskNode().getName(), task);
        return values;
    }
}
