package com.aryan.conduit.workflow.dto;

import com.aryan.conduit.execution.entity.DependencyCondition;

public record UpdateDependencyRequest(Long parentTaskId,

                                      Long childTaskId,

                                      DependencyCondition condition,

                                      String expression) {
}
