package com.aryan.conduit.workflow.service;

import com.aryan.conduit.workflow.entity.Workflow;
import com.aryan.conduit.workflow.entity.WorkflowStatus;
import com.aryan.conduit.workflow.entity.WorkflowVersion;
import com.aryan.conduit.workflow.repository.WorkflowRepository;
import com.aryan.conduit.workflow.repository.WorkflowVersionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class WorkflowService {
    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;

    @Transactional
    public Workflow createWorkflow(String name,String cronExpression){
        Workflow workflow=Workflow.builder()
                .name(name).status(WorkflowStatus.DRAFT)
                .createdAt(LocalDateTime.now()).cronExpression(cronExpression)
                .active(true)
                .build();

        workflow=workflowRepository.save(workflow);
        WorkflowVersion version=WorkflowVersion.builder().
        workflow(workflow).versionNumber(1)
                        .published(true)
                                .latest(true).createdAt(LocalDateTime.now()).publishedAt(LocalDateTime.now()).
                build();

        workflowVersionRepository.save(version);
        return workflow;
    }
}
