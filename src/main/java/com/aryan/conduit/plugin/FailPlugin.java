package com.aryan.conduit.plugin;

import com.aryan.conduit.workflow.entity.TaskNode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FailPlugin implements WorkflowPlugin {

    @Override
    public String getType() {
        return "FAIL";
    }

    @Override
    public PluginResult execute(
            TaskNode task,
            Map<String, Object> variables) {

        return PluginResult.builder()
                .success(false)
                .output("Intentional failure for retry/backoff testing")
                .build();
    }
}