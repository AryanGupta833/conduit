package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.Dependency;
import org.springframework.data.jpa.repository.JpaRepository;

import javax.crypto.DecapsulateException;
import java.util.List;

public interface DependencyRepository extends JpaRepository<Dependency,Long> {
    List<Dependency> findByParent_WorkflowVersion_Id(Long versionId);
    List<Dependency> findByChild_Id(Long childId);
    List<Dependency> findByParent_Id(Long parentId);

    boolean existsByParent_IdAndChild_Id(Long id, Long id1);
}
