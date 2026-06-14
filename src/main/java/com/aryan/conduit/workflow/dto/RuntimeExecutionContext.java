package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.TaskExecutionStatus;

import java.util.*;
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
    private final Set<Long> completedTasks=ConcurrentHashMap.newKeySet();
    private Set<Long> scheduledTasks=new HashSet<>();

    public Set<Long> getScheduledTasks() {
        return scheduledTasks;
    }

    public void setScheduledTasks(Set<Long> scheduledTasks) {
        this.scheduledTasks = scheduledTasks;
    }
}
