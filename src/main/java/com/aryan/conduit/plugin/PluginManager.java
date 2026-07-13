package com.aryan.conduit.plugin;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PluginManager {
    private final List<WorkflowPlugin> plugins;
    private final Map<String,WorkflowPlugin> registry=new HashMap<>();

    @PostConstruct
    public void initialize(){
        for(WorkflowPlugin plugin:plugins){
            registry.put(plugin.getType().toUpperCase(),plugin);
            System.out.println("Registered Plugin : "+ plugin.getType());
        }

    }

    public WorkflowPlugin getPlugin(String type){
        WorkflowPlugin plugin=registry.get(type.toUpperCase());
        if(plugin==null){
            throw new IllegalArgumentException("Plugin not found : "+type);
        }
        return plugin;
    }

    public List<String> getRegisteredPlugins(){
        return registry.keySet().stream().sorted().toList();
    }
}
