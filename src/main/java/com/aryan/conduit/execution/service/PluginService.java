package com.aryan.conduit.execution.service;


import com.aryan.conduit.workflow.dto.PluginResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PluginService {

    public List<PluginResponse> getPlugins() {

        return List.of(

                new PluginResponse(
                        "HTTP",
                        "Execute HTTP requests.",
                        List.of("url", "method", "headers", "body"),
                        List.of("status", "body"),
                        Map.of(
                                "url", "https://api.example.com",
                                "method", "GET"
                        )
                ),

                new PluginResponse(
                        "SHELL",
                        "Execute shell commands.",
                        List.of("command"),
                        List.of("stdout", "stderr"),
                        Map.of(
                                "command", "echo Hello World"
                        )
                ),

                new PluginResponse(
                        "LOGGER",
                        "Write messages to workflow logs.",
                        List.of("message"),
                        List.of(),
                        Map.of(
                                "message", "Workflow Started"
                        )
                ),

                new PluginResponse(
                        "SLEEP",
                        "Pause execution for a duration.",
                        List.of("seconds"),
                        List.of(),
                        Map.of(
                                "seconds", 5
                        )
                )

        );
    }
}