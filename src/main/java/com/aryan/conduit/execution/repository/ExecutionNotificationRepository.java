package com.aryan.conduit.execution.repository;

import com.aryan.conduit.execution.entity.ExecutionNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExecutionNotificationRepository extends JpaRepository<ExecutionNotification, Long> {
    Optional<ExecutionNotification> findByEventKey(String eventKey);
    List<ExecutionNotification> findTop200ByOrderByCreatedAtDesc();
    List<ExecutionNotification> findTop200ByReadAtIsNullOrderByCreatedAtDesc();
    long countByReadAtIsNull();

    @Modifying
    @Query("update ExecutionNotification n set n.readAt = :readAt where n.readAt is null")
    int markAllRead(@Param("readAt") java.time.LocalDateTime readAt);
}
