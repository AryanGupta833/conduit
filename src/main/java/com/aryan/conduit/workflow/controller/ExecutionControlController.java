package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.service.ExecutionContextService;
import com.aryan.conduit.execution.service.ExecutionControlService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class ExecutionControlController {
    private final ExecutionControlService executionControlService;

    @PostMapping("/{id}/pause")
    public String pause(@PathVariable Long id){
        executionControlService.pause(id);
        return "Execution Paused";
    }

    @PostMapping("/{id}/resume")
    public String resume(@PathVariable Long id){
        executionControlService.resume(id);
        return "Execution Resumed";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id){
        executionControlService.cancel(id);
        return "Execution Cancelled";
    }
}
