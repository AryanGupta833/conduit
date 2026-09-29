package com.aryan.conduit.docker;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/docker")
@RequiredArgsConstructor
public class DockerTestController {

    private final DockerExecutionService dockerExecutionService;

    @GetMapping("/test")
    public DockerExecutionResult testDocker() {

        return dockerExecutionService.execute(
                "alpine:latest",
                List.of("sleep", "30"),
                Duration.ofSeconds(3)
        );

    }
}