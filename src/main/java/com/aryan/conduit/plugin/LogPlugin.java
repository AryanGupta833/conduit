package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LogPlugin implements WorkflowPlugin {

    private static final Logger log = LoggerFactory.getLogger(LogPlugin.class);

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of("LOG", "Log Message", "Write task details and workflow variables to the application log.", "1.0.0");
    }

    @Override
    public PluginResult execute(PluginContext context) {

        log.info("Log plugin ran for task '{}' with {} workflow variables",
                context.taskName(), context.variables().size());

        return PluginResult.builder()
                .success(true)
                .output("Log plugin executed.")
                .build();
    }
}
