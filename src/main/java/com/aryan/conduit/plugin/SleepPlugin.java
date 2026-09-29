package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SleepPlugin implements WorkflowPlugin {

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of("SLEEP", "Sleep", "Pause task execution for three seconds.", "1.0.0");
    }

    @Override
    public PluginResult execute(PluginContext context) {

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
