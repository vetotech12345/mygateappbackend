package com.mygateapp.parking.repository;

import com.mygateapp.parking.entity.ParkingPermit;
import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.enums.ParkingEnums.PermitType;
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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingPermitRepository extends JpaRepository<ParkingPermit, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ParkingPermit p WHERE p.id = :id")
    Optional<ParkingPermit> findByIdForUpdate(@Param("id") UUID id);

    /** Permits of a vehicle number that are not ended (active, awaiting payment or suspended), newest first. */
    @Query("""
            SELECT p FROM ParkingPermit p
            WHERE p.societyId = :societyId AND p.vehicleNumber = :number
              AND p.status IN (com.mygateapp.parking.enums.ParkingEnums.PermitStatus.ACTIVE,
                               com.mygateapp.parking.enums.ParkingEnums.PermitStatus.PENDING_PAYMENT,
                               com.mygateapp.parking.enums.ParkingEnums.PermitStatus.SUSPENDED)
            ORDER BY p.validFrom DESC
            """)
    List<ParkingPermit> findOpenByNumber(@Param("societyId") UUID societyId, @Param("number") String number);

    List<ParkingPermit> findByVehicleIdAndStatusIn(UUID vehicleId, Collection<PermitStatus> statuses);

    List<ParkingPermit> findByAllocationIdAndStatusIn(UUID allocationId, Collection<PermitStatus> statuses);

    List<ParkingPermit> findByVehicleIdInAndStatusIn(Collection<UUID> vehicleIds, Collection<PermitStatus> statuses);

    List<ParkingPermit> findByAllocationIdIn(Collection<UUID> allocationIds);

    List<ParkingPermit> findByRequestId(UUID requestId);

    @Query("SELECT p FROM ParkingPermit p WHERE p.flatId IN :flatIds ORDER BY p.validFrom DESC")
    List<ParkingPermit> findByFlats(@Param("flatIds") Collection<UUID> flatIds);

    @Query("""
            SELECT p FROM ParkingPermit p
            WHERE p.societyId = :societyId
              AND (:status IS NULL OR p.status = :status)
              AND (:type IS NULL OR p.permitType = :type)
              AND (:q IS NULL OR p.vehicleNumber LIKE :q ESCAPE '\\' OR UPPER(p.permitNumber) LIKE :q ESCAPE '\\')
            """)
    Page<ParkingPermit> search(@Param("societyId") UUID societyId, @Param("status") PermitStatus status,
                               @Param("type") PermitType type, @Param("q") String upperLikePattern, Pageable pageable);

    /** {@code q} is a normalised term (no spaces, hyphens or dots); the permit number is compared the same way. */
    @Query("""
            SELECT p FROM ParkingPermit p
            WHERE p.societyId = :societyId
              AND REPLACE(REPLACE(REPLACE(UPPER(p.permitNumber), '-', ''), ' ', ''), '.', '') LIKE :q ESCAPE '\\'
            ORDER BY p.permitNumber
            """)
    List<ParkingPermit> searchByNumber(@Param("societyId") UUID societyId, @Param("q") String upperLikePattern,
                                       Pageable pageable);

    @Query("""
            SELECT p.id FROM ParkingPermit p
            WHERE p.status IN (com.mygateapp.parking.enums.ParkingEnums.PermitStatus.ACTIVE,
                               com.mygateapp.parking.enums.ParkingEnums.PermitStatus.PENDING_PAYMENT,
                               com.mygateapp.parking.enums.ParkingEnums.PermitStatus.SUSPENDED)
              AND p.validUntil IS NOT NULL AND p.validUntil <= :now
            """)
    List<UUID> findEndedIds(@Param("now") Instant now);

    @Query("""
            SELECT p FROM ParkingPermit p
            WHERE p.status = com.mygateapp.parking.enums.ParkingEnums.PermitStatus.ACTIVE
              AND p.validUntil IS NOT NULL AND p.validUntil > :now AND p.validUntil <= :horizon
              AND p.expiryRemindedAt IS NULL AND p.permitType <> com.mygateapp.parking.enums.ParkingEnums.PermitType.VISITOR
            """)
    List<ParkingPermit> findExpiringUnreminded(@Param("now") Instant now, @Param("horizon") Instant horizon);
}
