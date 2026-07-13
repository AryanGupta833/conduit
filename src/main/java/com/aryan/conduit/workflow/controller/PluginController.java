package com.aryan.conduit.workflow.controller;

import com.aryan.conduit.execution.service.PluginService;
import com.aryan.conduit.workflow.dto.PluginResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginService pluginService;

    @GetMapping
    public List<PluginResponse> listPlugins() {

        return pluginService.getPlugins();
    }
}