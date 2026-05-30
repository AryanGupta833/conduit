package com.aryan.conduit.workflow.service;

import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.entity.WorkflowStatus;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class WorkflowService {
    private final WorkflowRepository workflowRepository;

    public Workflow createWorkflow(String name){
        Workflow workflow=Workflow.builder().
                name(name)
                .status(WorkflowStatus.DRAFT)
                .createdAt(LocalDateTime.now())
                .build();
        return workflowRepository.save(workflow);
    }
}
