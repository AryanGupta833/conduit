package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.PluginResult;
import com.aryan.conduit.plugin.WorkflowPlugin;
import com.aryan.conduit.workflow.entity.TaskNode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LogPlugin implements WorkflowPlugin {

    @Override
    public String getType() {
        return "LOG";
    }

    @Override
    public PluginResult execute(TaskNode task,
                                Map<String, Object> variables) {

        System.out.println("========== LOG PLUGIN ==========");
        System.out.println("Task : " + task.getName());
        System.out.println("Variables : " + variables);
        System.out.println("================================");

        return PluginResult.builder()
                .success(true)
                .output("Log plugin executed.")
                .build();
    }
}