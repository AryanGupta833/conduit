package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.service.ExecutionContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/context")
@RequiredArgsConstructor
public class ExecutionContextController {
    private final ExecutionContextService executionContextService;

    @PostMapping("/{executionId}/test")
    public String test(@PathVariable Long executionId){
        executionContextService.putVariable(executionId,"username","Aryan");

        return "saved";
    }
}
