package com.aryan.conduit.execution.retry;

public class TaskExecutionCancelledException extends RuntimeException {
    public TaskExecutionCancelledException(String message) {
        super(message);
    }
}
