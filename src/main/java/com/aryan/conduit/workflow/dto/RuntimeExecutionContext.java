package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.TaskExecutionStatus;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RuntimeExecutionContext {

    private final Queue<Long> readyQueue=new ConcurrentLinkedQueue<>();
    private final Map<Long, TaskExecutionStatus> taskStatuses=new ConcurrentHashMap<>();
    public Queue<Long> getReadyQueue(){
        return readyQueue;
    }
    public Map<Long,TaskExecutionStatus> getTaskStatuses(){
        return taskStatuses;
    }
}
