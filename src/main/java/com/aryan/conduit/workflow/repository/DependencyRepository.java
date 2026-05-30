package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.Dependency;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DependencyRepository extends JpaRepository<Dependency,Long> {
}
