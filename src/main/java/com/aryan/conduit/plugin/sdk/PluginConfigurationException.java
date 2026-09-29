package com.aryan.conduit.plugin.sdk;

/** A readable configuration error that Conduit can report through its normal task failure path. */
public class PluginConfigurationException extends RuntimeException {
    public PluginConfigurationException(String message) { super(message); }
    public PluginConfigurationException(String message, Throwable cause) { super(message, cause); }
}
