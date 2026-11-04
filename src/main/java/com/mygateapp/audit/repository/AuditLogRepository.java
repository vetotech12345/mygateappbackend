package com.mygateapp.audit.repository;

import com.mygateapp.audit.entity.AuditLog;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query(value = """
            SELECT a FROM AuditLog a
            WHERE (:allSocieties = true OR a.societyId = :societyId)
              AND (:action IS NULL OR a.action = :action)
              AND (:entityType IS NULL OR a.entityType = :entityType)
              AND (:entityId IS NULL OR a.entityId = :entityId)
              AND (:actorId IS NULL OR a.actorUserId = :actorId)
              AND a.occurredAt >= :from AND a.occurredAt < :to
            ORDER BY a.occurredAt DESC
            """)
    Page<AuditLog> search(@Param("allSocieties") boolean allSocieties, @Param("societyId") UUID societyId,
                          @Param("action") String action, @Param("entityType") String entityType,
                          @Param("entityId") UUID entityId, @Param("actorId") UUID actorId,
                          @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
