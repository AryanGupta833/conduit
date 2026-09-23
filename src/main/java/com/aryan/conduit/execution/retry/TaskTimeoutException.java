package com.aryan.conduit.execution.retry;

public class TaskTimeoutException extends RuntimeException {

    public TaskTimeoutException(String message) {
        super(message);
    }
}