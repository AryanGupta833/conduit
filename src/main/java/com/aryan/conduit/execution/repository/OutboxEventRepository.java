package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, Long> {

    @Query(value = "select * from outbox_events where status = 'PENDING' order by (priority + floor(extract(epoch from (now() - created_at)) / 60)) desc, created_at asc, id asc limit 1 for update skip locked", nativeQuery = true)
    List<OutboxEvent> lockNextPending();
}
