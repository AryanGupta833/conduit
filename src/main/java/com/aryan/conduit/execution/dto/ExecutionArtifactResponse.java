package com.aryan.conduit.execution.dto;

import java.time.Instant;

public record ExecutionArtifactResponse(String path, long sizeBytes, Instant modifiedAt,
                                        Long createdByTaskId, String area) { }
