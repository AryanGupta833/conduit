package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.OutboxEvent;
import com.aryan.conduit.execution.entity.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByStatusOrderByIdAsc(
            OutboxEventStatus status
    );
}