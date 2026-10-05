package com.aryan.conduit.plugin.sdk;

import java.io.IOException;
import java.io.InputStream;

/** Execution-scoped artifact access. Paths are logical and relative to the execution workspace. */
public interface PluginWorkspace {
    InputStream openRead(String logicalPath) throws IOException;
    long writeAtomic(String logicalPath, InputStream content, boolean overwrite, boolean createDirectories) throws IOException;
    long usedBytes() throws IOException;
    long maximumBytes();
}
