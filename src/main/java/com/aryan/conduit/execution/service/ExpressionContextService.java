package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ExpressionContextService {

    private final ExecutionContextService executionContextService;
    private final TaskOutputService taskOutputService;
    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final ObjectMapper objectMapper;

    public Map<String, Object> buildContext(Long workflowExecutionId) {

        Map<String, Object> context =
                new HashMap<>(
                        executionContextService.getVariables(
                                workflowExecutionId
                        )
                );

        WorkflowExecution workflowExecution =
                workflowExecutionRepository
                        .findById(workflowExecutionId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Workflow execution not found: "
                                                + workflowExecutionId
                                )
                        );

        Long workflowVersionId =
                workflowExecution
                        .getWorkflowVersion()
                        .getId();

        List<TaskNode> taskNodes =
                taskNodeRepository
                        .findByWorkflowVersion_Id(
                                workflowVersionId
                        );

        for (TaskNode taskNode : taskNodes) {

            String taskName = taskNode.getName();

            if (taskName == null || taskName.isBlank()) {
                continue;
            }

            Map<String, Object> taskOutput =
                    taskOutputService.getOutput(
                            workflowExecutionId,
                            taskNode.getId()
                    );

            if (taskOutput == null) {
                continue;
            }

            Map<String, Object> normalizedOutput =
                    normalizeTaskOutput(taskOutput);

            context.put(taskName, normalizedOutput);
        }

        return context;
    }

    private Map<String, Object> normalizeTaskOutput(
            Map<String, Object> taskOutput
    ) {

        Map<String, Object> normalized =
                new HashMap<>(taskOutput);

        Object output =
                normalized.get("output");

        if (!(output instanceof String outputString)
                || outputString.isBlank()) {
            return normalized;
        }

        String trimmed = outputString.trim();

        /*
         * Only parse JSON objects/arrays.
         * Ordinary plugin output such as "SUCCESS" remains a String.
         */
        if (!(trimmed.startsWith("{")
                || trimmed.startsWith("["))) {
            return normalized;
        }

        try {

            JsonNode jsonNode =
                    objectMapper.readTree(trimmed);

            if (jsonNode == null
                    || !(jsonNode.isObject()
                    || jsonNode.isArray())) {
                return normalized;
            }

            Object parsedOutput =
                    objectMapper.convertValue(
                            jsonNode,
                            Object.class
                    );

            normalized.put(
                    "output",
                    parsedOutput
            );

        } catch (Exception ignored) {

            /*
             * If the plugin output is not valid JSON,
             * preserve it as the original String.
             */
        }

        return normalized;
    }
}