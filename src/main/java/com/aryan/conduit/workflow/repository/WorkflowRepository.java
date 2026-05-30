package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository extends JpaRepository<Workflow,Long> {
}
