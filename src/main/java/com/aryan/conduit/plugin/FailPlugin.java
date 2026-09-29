package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import org.springframework.stereotype.Component;

@Component
public class FailPlugin implements WorkflowPlugin {

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of("FAIL", "Fail", "Intentionally fail a task for retry behavior testing.", "1.0.0");
    }

    @Override
    public PluginResult execute(
            PluginContext context) {

        return PluginResult.builder()
                .success(false)
                .output("Intentional failure for retry/backoff testing")
                .build();
    }
}
