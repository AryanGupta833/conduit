package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.TaskNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskNodeRepository extends JpaRepository<TaskNode,Long> {
    List<TaskNode> findByWorkflow_Id(Long workflowId);
}
