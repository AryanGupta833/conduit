package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.ExecutionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, Long> {
    List<ExecutionEvent> findByWorkflowExecution_IdOrderByOccurredAtAscIdAsc(Long executionId);
}
