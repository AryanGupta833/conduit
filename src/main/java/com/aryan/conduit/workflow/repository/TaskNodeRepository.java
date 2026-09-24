package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.TaskNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskNodeRepository extends JpaRepository<TaskNode,Long> {
    List<TaskNode> findByWorkflowVersion_Id(Long versionId);
    Optional<TaskNode> findByWorkflowVersion_IdAndName(Long versionId, String name);
}
