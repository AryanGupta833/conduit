package com.aryan.conduit.execution.service;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskOutputService {
    private final ExecutionContextService executionContextService;

    public void storeOutput(Long workflowExecutionId, Long taskId, Map<String,Object> output){
        executionContextService.putVariable(workflowExecutionId,"task_"+taskId,output);
    }

    public Map<String,Object> getOutput(Long workflowExecutionId,Long taskId){
        Map<String,Object> variables=executionContextService.getVariables(workflowExecutionId);

        return (Map<String, Object>) variables.get("task_"+taskId);
    }


}
