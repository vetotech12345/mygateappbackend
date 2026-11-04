package com.mygateapp.visitors.repository;

import com.mygateapp.visitors.entity.Visitor;
import com.mygateapp.visitors.enums.VisitorEnums.ApprovalStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitorRepository extends JpaRepository<Visitor, UUID>, JpaSpecificationExecutor<Visitor> {

    /** Row lock for state transitions: concurrent approve/entry/exit on the same visitor are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Visitor v WHERE v.id = :id")
    Optional<Visitor> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT v FROM Visitor v
            WHERE v.societyId = :societyId AND v.passCode = :passCode AND v.source = com.mygateapp.visitors.enums.VisitorEnums.Source.INVITATION
            ORDER BY v.requestedAt DESC
            """)
    List<Visitor> findInvitationsByPassCode(@Param("societyId") UUID societyId, @Param("passCode") String passCode);

    @Query("""
            SELECT COUNT(v) > 0 FROM Visitor v
            WHERE v.societyId = :societyId AND v.passCode = :passCode
              AND v.approvalStatus = com.mygateapp.visitors.enums.VisitorEnums.ApprovalStatus.APPROVED
              AND v.movement.status = com.mygateapp.common.gate.MovementStatus.NOT_ENTERED
            """)
    boolean activePassCodeExists(@Param("societyId") UUID societyId, @Param("passCode") String passCode);

    /** Invitations whose window has ended without entry. */
    @Query("""
            SELECT v.id FROM Visitor v
            WHERE v.source = com.mygateapp.visitors.enums.VisitorEnums.Source.INVITATION
              AND v.approvalStatus = com.mygateapp.visitors.enums.VisitorEnums.ApprovalStatus.APPROVED
              AND v.movement.status = com.mygateapp.common.gate.MovementStatus.NOT_ENTERED
              AND v.validUntil < :now
            """)
    List<UUID> findExpiredInvitationIds(@Param("now") Instant now);

    @Query(value = """
            SELECT v.id FROM visitors v JOIN society_settings s ON s.society_id = v.society_id
            WHERE v.source = 'WALK_IN' AND v.approval_status = 'PENDING'
              AND v.requested_at < CAST(:now AS timestamptz) - make_interval(mins => s.pending_visitor_expiry_minutes)
            """, nativeQuery = true)
    List<UUID> findStalePendingIds(@Param("now") Instant now);

    @Query(value = """
            SELECT v.id FROM visitors v JOIN society_settings s ON s.society_id = v.society_id
            WHERE v.anonymized_at IS NULL AND v.movement_status <> 'INSIDE'
              AND v.requested_at < CAST(:now AS timestamptz) - make_interval(days => s.visitor_retention_days)
            LIMIT 500
            """, nativeQuery = true)
    List<UUID> findIdsDueForAnonymization(@Param("now") Instant now);

    long countBySocietyIdAndApprovalStatus(UUID societyId, ApprovalStatus status);
}
