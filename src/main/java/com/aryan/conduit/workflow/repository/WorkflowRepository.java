package com.aryan.conduit.workflow.repository;

import com.aryan.conduit.workflow.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface WorkflowRepository extends JpaRepository<Workflow,Long> {
    List<Workflow> findByActiveTrue();
    List<Workflow> findByOwner_IdOrderByIdDesc(Long ownerId);
    List<Workflow> findByOwnerIsNull();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workflow w where w.id = :id")
    java.util.Optional<Workflow> findByIdForUpdate(@Param("id") Long id);
}
