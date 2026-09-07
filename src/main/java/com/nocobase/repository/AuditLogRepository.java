package com.nocobase.repository;

import com.nocobase.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByResource(String resource, Pageable pageable);

    Page<AuditLog> findByResourceAndResourceKey(String resource, String resourceKey, Pageable pageable);

    Page<AuditLog> findByActorUserId(Long actorUserId, Pageable pageable);

    Page<AuditLog> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end, Pageable pageable);

    List<AuditLog> findByRequestId(String requestId);
}