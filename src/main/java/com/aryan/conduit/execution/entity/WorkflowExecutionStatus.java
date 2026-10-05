package com.aryan.conduit.execution.entity;

public enum WorkflowExecutionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    PAUSED,
    CANCELLED,
    WAITING_FOR_HUMAN,
    TIMED_OUT
}
