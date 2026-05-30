package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.Dependency;
import org.springframework.data.jpa.repository.JpaRepository;

import javax.crypto.DecapsulateException;
import java.util.List;

public interface DependencyRepository extends JpaRepository<Dependency,Long> {
    List<Dependency> findByParent_Workflow_Id(Long workflowId);
}
