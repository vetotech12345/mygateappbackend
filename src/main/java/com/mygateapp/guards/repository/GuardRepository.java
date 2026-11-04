package com.mygateapp.guards.repository;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.guards.entity.SecurityGuard;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuardRepository extends JpaRepository<SecurityGuard, UUID> {

    Optional<SecurityGuard> findBySocietyIdAndUserId(UUID societyId, UUID userId);

    List<SecurityGuard> findByUserId(UUID userId);

    @Query(value = """
            SELECT g FROM SecurityGuard g JOIN g.user u
            WHERE g.societyId = :societyId
              AND (:status IS NULL OR g.status = :status)
              AND (:q IS NULL OR LOWER(u.name) LIKE :q ESCAPE '\\' OR u.mobile LIKE :q ESCAPE '\\'
                   OR LOWER(g.employeeCode) LIKE :q ESCAPE '\\')
            """)
    Page<SecurityGuard> search(@Param("societyId") UUID societyId, @Param("status") ActiveStatus status,
                               @Param("q") String likePattern, Pageable pageable);

    long countBySocietyIdAndStatus(UUID societyId, ActiveStatus status);

    long countBySocietyIdAndStatusAndOnDutyTrue(UUID societyId, ActiveStatus status);

    /** User ids of active guards of the society currently on duty, optionally at one gate. */
    @Query("""
            SELECT g.user.id FROM SecurityGuard g
            WHERE g.societyId = :societyId AND g.status = com.mygateapp.common.ActiveStatus.ACTIVE AND g.onDuty = true
              AND (:gateId IS NULL OR EXISTS (SELECT 1 FROM GuardAssignment a
                   WHERE a.guardId = g.id AND a.active = true AND a.gate.id = :gateId))
            """)
    List<UUID> findOnDutyGuardUserIds(@Param("societyId") UUID societyId, @Param("gateId") UUID gateId);
}
