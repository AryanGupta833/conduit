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

        executionContextService.putVariable(
                workflowExecutionId,
                "task_" + taskId,
                output
        );
    }

    public Map<String, Object> getOutput(
            Long workflowExecutionId,
            Long taskId
    ) {

        Map<String, Object> variables =
                executionContextService.getVariables(
                        workflowExecutionId
                );

        Object output =
                variables.get("task_" + taskId);

        if (output == null) {
            return null;
        }

        if (!(output instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "Stored task output is not an object for task "
                            + taskId
            );
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result =
                (Map<String, Object>) map;

        return result;
    }
}