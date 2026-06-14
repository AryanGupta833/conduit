package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.ExecutionContextEntity;
import com.aryan.conduit.execution.entity.ExecutionContextEntity;
import com.aryan.conduit.execution.repository.ExecutionContextRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ExecutionContextService {
    private final ExecutionContextRepository executionContextRepository;

    private final ObjectMapper objectMapper;

    public Map<String,Object> getVariables(Long workflowExecutionId){
        try{
            ExecutionContextEntity context=executionContextRepository.findByWorkflowExecution_Id(workflowExecutionId).orElseThrow();
                    return objectMapper.readValue(context.getVariableJson(), new TypeReference<>() {
                    });

        }
        catch (Exception e){
            throw new RuntimeException();
        }
    }

    public void putVariable(Long workflowExecutionId,String key,Object value){
        try{
            ExecutionContextEntity context=executionContextRepository.findByWorkflowExecution_Id(workflowExecutionId).orElseThrow();

            Map<String,Object> variables=objectMapper.readValue(context.getVariableJson(), new TypeReference<>() {
            });

            variables.put(key,value);
            context.setVariableJson(objectMapper.writeValueAsString(variables));

            executionContextRepository.save(context);
        }
        catch (Exception e){
            throw new RuntimeException(e);
        }
    }
}
