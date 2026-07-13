package com.aryan.conduit.workflow.dto;

import java.time.LocalDateTime;

public record WorkflowVersionResponse(

        Long id,
        Long workflowId,

        Integer versionNumber,

        Boolean latest,

        Boolean published,

        LocalDateTime createdAt,
        LocalDateTime publishedAt

) {
}