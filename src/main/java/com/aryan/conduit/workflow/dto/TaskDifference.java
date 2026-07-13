package com.aryan.conduit.workflow.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record TaskDifference(String taskName, List<String> changes) {

}
