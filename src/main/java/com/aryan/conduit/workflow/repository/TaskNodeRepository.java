package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.TaskNode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskNodeRepository extends JpaRepository<TaskNode,Long> {
}
