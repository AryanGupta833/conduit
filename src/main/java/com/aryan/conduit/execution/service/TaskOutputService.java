package com.aryan.conduit.execution.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskOutputService {

    private final ExecutionContextService executionContextService;

    public void storeOutput(
            Long workflowExecutionId,
            Long taskId,
            Map<String, Object> output
    ) {

        executionContextService.commitTaskResult(workflowExecutionId, taskId, "task_" + taskId,
                "SUCCESS", output);
    }

    public Map<String, Object> getOutput(
            Long workflowExecutionId,
            Long taskId
    ) {

        Object output = executionContextService.getTaskResults(workflowExecutionId).get(String.valueOf(taskId));

        if (output == null) {
            return null;
        }

        if (!(output instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "Stored task output is not an object for task "
                            + taskId
            );
        }

        return new java.util.LinkedHashMap<>((Map<String, Object>) map);
    }
}
