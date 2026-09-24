package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.ExecutionContextEntity;
import com.aryan.conduit.execution.repository.ExecutionContextRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ExecutionContextService {

    private final ExecutionContextRepository executionContextRepository;
    private final ObjectMapper objectMapper;

    public Map<String, Object> getVariables(Long workflowExecutionId) {

        try {

            ExecutionContextEntity context =
                    executionContextRepository
                            .findByWorkflowExecution_Id(
                                    workflowExecutionId
                            )
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Execution context not found for workflow execution "
                                                    + workflowExecutionId
                                    )
                            );

            if (context.getVariableJson() == null ||
                    context.getVariableJson().isBlank()) {

                return new HashMap<>();
            }

            return objectMapper.readValue(
                    context.getVariableJson(),
                    new TypeReference<Map<String, Object>>() {}
            );

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed to read execution variables for workflow execution "
                            + workflowExecutionId,
                    e
            );
        }
    }

    @Transactional
    public void putVariable(
            Long workflowExecutionId,
            String key,
            Object value
    ) {

        int maxAttempts = 3;

        for (int attempt = 1;
             attempt <= maxAttempts;
             attempt++) {

            try {

                ExecutionContextEntity context =
                        executionContextRepository
                                .findByWorkflowExecution_Id(
                                        workflowExecutionId
                                )
                                .orElseThrow(() ->
                                        new IllegalStateException(
                                                "Execution context not found for workflow execution "
                                                        + workflowExecutionId
                                        )
                                );

                Map<String, Object> variables;

                if (context.getVariableJson() == null ||
                        context.getVariableJson().isBlank()) {

                    variables = new HashMap<>();

                } else {

                    variables = objectMapper.readValue(
                            context.getVariableJson(),
                            new TypeReference<Map<String, Object>>() {}
                    );
                }

                variables.put(key, value);

                context.setVariableJson(
                        objectMapper.writeValueAsString(
                                variables
                        )
                );

                executionContextRepository.saveAndFlush(context);

                return;

            } catch (ObjectOptimisticLockingFailureException e) {

                if (attempt == maxAttempts) {

                    throw new IllegalStateException(
                            "Failed to update execution context after "
                                    + maxAttempts
                                    + " attempts for workflow execution "
                                    + workflowExecutionId,
                            e
                    );
                }

            } catch (Exception e) {

                throw new RuntimeException(
                        "Failed to update execution variable '"
                                + key
                                + "' for workflow execution "
                                + workflowExecutionId,
                        e
                );
            }
        }
    }
}