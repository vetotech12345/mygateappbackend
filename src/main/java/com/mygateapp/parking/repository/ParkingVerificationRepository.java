package com.mygateapp.parking.repository;

import com.mygateapp.parking.entity.ParkingVerification;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingVerificationRepository extends JpaRepository<ParkingVerification, UUID> {

    /**
     * Atomically marks a verification as used. Returns 1 only for an unused, unexpired verification of this guard
     * and society, so two concurrent entries with the same id cannot both succeed.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ParkingVerification v SET v.usedAt = :now
            WHERE v.id = :id AND v.usedAt IS NULL AND v.expiresAt > :now
              AND v.guardUserId = :guardUserId AND v.societyId = :societyId
            """)
    int consume(@Param("id") UUID id, @Param("guardUserId") UUID guardUserId, @Param("societyId") UUID societyId,
                @Param("now") Instant now);

    /** Deletes old verifications that no session references. */
    @Modifying
    @Query(value = """
            DELETE FROM parking_verifications v
            WHERE v.created_at < CAST(:before AS timestamptz)
              AND NOT EXISTS (SELECT 1 FROM parking_sessions s WHERE s.verification_id = v.id)
            """, nativeQuery = true)
    int deleteUnusedBefore(@Param("before") Instant before);
}
