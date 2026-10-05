package com.aryan.conduit.execution.controller;

import com.aryan.conduit.execution.dto.ExecutionArtifactResponse;
import com.aryan.conduit.execution.service.ExecutionArtifactService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class ExecutionArtifactController {
    private final ExecutionArtifactService artifacts;

    @GetMapping("/{executionId}/artifacts")
    public List<ExecutionArtifactResponse> list(@PathVariable Long executionId) {
        return artifacts.list(executionId);
    }
}
