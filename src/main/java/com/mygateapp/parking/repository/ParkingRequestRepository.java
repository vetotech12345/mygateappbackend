package com.mygateapp.parking.repository;

import com.mygateapp.parking.entity.ParkingRequest;
import com.mygateapp.parking.enums.ParkingEnums.RequestStatus;
import com.mygateapp.parking.enums.ParkingEnums.RequestType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingRequestRepository extends JpaRepository<ParkingRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ParkingRequest r WHERE r.id = :id")
    Optional<ParkingRequest> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT r FROM ParkingRequest r
            WHERE r.societyId = :societyId
              AND (:restrict = false OR r.flatId IN :flatIds)
              AND (:status IS NULL OR r.status = :status)
              AND (:type IS NULL OR r.type = :type)
              AND (:visitorOnly = false OR r.type = com.mygateapp.parking.enums.ParkingEnums.RequestType.VISITOR_SLOT)
            """)
    Page<ParkingRequest> search(@Param("societyId") UUID societyId, @Param("restrict") boolean restrict,
                                @Param("flatIds") Collection<UUID> flatIds, @Param("status") RequestStatus status,
                                @Param("type") RequestType type, @Param("visitorOnly") boolean visitorOnly,
                                Pageable pageable);

    boolean existsByFlatIdAndTypeAndVehicleNumberAndStatus(UUID flatId, RequestType type, String vehicleNumber,
                                                           RequestStatus status);

    /** Approved, unused visitor parking for a number whose window covers [now, now + early arrival tolerance]. */
    @Query("""
            SELECT r FROM ParkingRequest r
            WHERE r.societyId = :societyId AND r.vehicleNumber = :number
              AND r.type = com.mygateapp.parking.enums.ParkingEnums.RequestType.VISITOR_SLOT
              AND r.status = com.mygateapp.parking.enums.ParkingEnums.RequestStatus.APPROVED
              AND r.consumedAt IS NULL AND r.validFrom <= :earliest AND r.validUntil > :now
            ORDER BY r.validFrom
            """)
    List<ParkingRequest> findUsableVisitorRequests(@Param("societyId") UUID societyId, @Param("number") String number,
                                                   @Param("now") Instant now, @Param("earliest") Instant earliest);

    /** Pending requests whose window has ended, and approved visitor requests nobody used. */
    @Query("""
            SELECT r.id FROM ParkingRequest r
            WHERE r.validUntil IS NOT NULL AND r.validUntil <= :now
              AND (r.status = com.mygateapp.parking.enums.ParkingEnums.RequestStatus.PENDING
                   OR (r.status = com.mygateapp.parking.enums.ParkingEnums.RequestStatus.APPROVED
                       AND r.type = com.mygateapp.parking.enums.ParkingEnums.RequestType.VISITOR_SLOT
                       AND r.consumedAt IS NULL))
            """)
    List<UUID> findExpirableIds(@Param("now") Instant now);

    @Query("""
            SELECT COUNT(r) FROM ParkingRequest r
            WHERE r.societyId = :societyId AND r.type = com.mygateapp.parking.enums.ParkingEnums.RequestType.VISITOR_SLOT
              AND r.status = com.mygateapp.parking.enums.ParkingEnums.RequestStatus.APPROVED
              AND r.consumedAt IS NULL AND r.validFrom <= :now AND r.validUntil > :now
            """)
    long countWaitingVisitorVehicles(@Param("societyId") UUID societyId, @Param("now") Instant now);

    long countBySocietyIdAndStatus(UUID societyId, RequestStatus status);

    /** Retention: removes visitor name/mobile from visitor requests older than the society's retention period. */
    @Modifying
    @Query(value = """
            UPDATE parking_requests r SET visitor_name = 'Visitor (redacted)', visitor_mobile = NULL,
                   visit_purpose = NULL, anonymized_at = CAST(:now AS timestamptz), updated_at = CAST(:now AS timestamptz)
            FROM society_settings s
            WHERE s.society_id = r.society_id AND r.request_type = 'VISITOR_SLOT' AND r.anonymized_at IS NULL
              AND r.status <> 'PENDING'
              AND r.created_at < CAST(:now AS timestamptz) - make_interval(days => s.visitor_retention_days)
            """, nativeQuery = true)
    int anonymizeVisitorData(@Param("now") Instant now);
}
