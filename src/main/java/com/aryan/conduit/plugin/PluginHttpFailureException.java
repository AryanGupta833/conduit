package com.aryan.conduit.plugin;

/** Carries safe HTTP retry classification from network plugins into the task retry loop. */
public final class PluginHttpFailureException extends RuntimeException {
    private final boolean retryable;
    private final long retryAfterMillis;

    public PluginHttpFailureException(int status, boolean retryable, long retryAfterMillis) {
        super("HTTP request failed with status " + status);
        this.retryable = retryable;
        this.retryAfterMillis = Math.max(0, Math.min(retryAfterMillis, 300_000));
    }

    public boolean retryable() { return retryable; }
    public long retryAfterMillis() { return retryAfterMillis; }
}
