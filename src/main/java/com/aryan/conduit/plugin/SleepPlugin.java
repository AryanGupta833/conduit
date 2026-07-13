package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.PluginResult;
import com.aryan.conduit.plugin.WorkflowPlugin;
import com.aryan.conduit.workflow.entity.TaskNode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SleepPlugin implements WorkflowPlugin {

    @Override
    public String getType() {
        return "SLEEP";
    }

    @Override
    public PluginResult execute(TaskNode task,
                                Map<String, Object> variables) {

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return PluginResult.builder()
                .success(true)
                .output("Slept for 3 seconds")
                .build();
    }
}