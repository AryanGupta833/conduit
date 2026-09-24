package com.aryan.conduit.execution.retry;

public class TaskLeaseLostException extends RuntimeException {
    public TaskLeaseLostException(String message) {
        super(message);
    }
}
