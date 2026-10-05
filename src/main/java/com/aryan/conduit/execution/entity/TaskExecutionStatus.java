package com.aryan.conduit.execution.entity;

public enum TaskExecutionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    RETRYING,
    SKIPPED,
    TIMEOUT,
    QUEUED,
    WAITING_FOR_HUMAN,
}
