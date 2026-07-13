package com.aryan.conduit.plugin;

import com.aryan.conduit.workflow.entity.TaskNode;

import java.util.Map;

public interface WorkflowPlugin {

    String getType();
    PluginResult execute(TaskNode task, Map<String,Object> variables);
}
