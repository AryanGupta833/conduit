package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowRepository extends JpaRepository<Workflow,Long> {
    List<Workflow> findByActiveTrue();
}
