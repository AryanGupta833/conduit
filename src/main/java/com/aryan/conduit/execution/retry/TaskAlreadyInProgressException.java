package com.aryan.conduit.execution.retry;

public class TaskAlreadyInProgressException extends RuntimeException {
    public TaskAlreadyInProgressException(String idempotencyKey) {
        super("Task is already being executed: " + idempotencyKey);
    }
}
