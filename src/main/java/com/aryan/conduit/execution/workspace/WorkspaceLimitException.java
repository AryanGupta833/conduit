package com.aryan.conduit.execution.workspace;

import java.io.IOException;

public final class WorkspaceLimitException extends IOException {
    public WorkspaceLimitException(String message) { super(message); }
}
