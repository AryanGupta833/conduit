package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.WorkflowExecution;
import com.aryan.conduit.execution.repository.WorkflowExecutionRepository;
import com.aryan.conduit.workflow.entity.TaskNode;
import com.aryan.conduit.workflow.repository.TaskNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskOutputResolver {

    private final TaskNodeRepository taskNodeRepository;
    private final TaskOutputService taskOutputService;
    private final WorkflowExecutionRepository workflowExecutionRepository;

    public Map<String, Object> getTaskOutput(
            Long workflowExecutionId,
            String taskName
    ) {

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

        TaskNode taskNode =
                taskNodeRepository
                        .findByWorkflowVersion_IdAndName(
                                workflowVersionId,
                                taskName
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Task not found in workflow version: "
                                                + taskName
                                )
                        );

        return taskOutputService.getOutput(
                workflowExecutionId,
                taskNode.getId()
        );
    }

    public Object resolve(
            Long workflowExecutionId,
            String taskName,
            String path
    ) {

        Map<String, Object> taskOutput =
                getTaskOutput(
                        workflowExecutionId,
                        taskName
                );

        if (taskOutput == null) {
            return null;
        }

        Object current = taskOutput;

        if (path == null || path.isBlank()) {
            return current;
        }

        String[] parts =
                path.split("\\.");

        for (String part : parts) {

            if (!(current instanceof Map<?, ?> map)) {

                throw new IllegalArgumentException(
                        "Cannot resolve '"
                                + part
                                + "' because current value is not an object"
                );
            }

            current = map.get(part);

            if (current == null) {
                return null;
            }
        }

        return current;
    }
}