package com.mygateapp.parking.repository;

import com.mygateapp.parking.entity.ParkingAllocation;
import com.mygateapp.parking.enums.ParkingEnums.AllocationStatus;
import com.mygateapp.parking.enums.ParkingEnums.AllocationType;
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

public interface ParkingAllocationRepository extends JpaRepository<ParkingAllocation, UUID> {

    String LIVE = "(com.mygateapp.parking.enums.ParkingEnums.AllocationStatus.ACTIVE, "
            + "com.mygateapp.parking.enums.ParkingEnums.AllocationStatus.PENDING, "
            + "com.mygateapp.parking.enums.ParkingEnums.AllocationStatus.SUSPENDED)";

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ParkingAllocation a WHERE a.id = :id")
    Optional<ParkingAllocation> findByIdForUpdate(@Param("id") UUID id);

    /** Live allocations of a slot overlapping [from, until). Pass {@code TimeRanges.MAX} for an open end. */
    @Query("SELECT a FROM ParkingAllocation a WHERE a.slotId = :slotId AND a.status IN " + LIVE + """
             AND a.validFrom < :until AND (a.validUntil IS NULL OR a.validUntil > :from)
             AND (:excludeId IS NULL OR a.id <> :excludeId)
            """)
    List<ParkingAllocation> findOverlapping(@Param("slotId") UUID slotId, @Param("from") Instant from,
                                            @Param("until") Instant until, @Param("excludeId") UUID excludeId);

    /** Live allocations of the given slots overlapping [from, until). */
    @Query("SELECT a FROM ParkingAllocation a WHERE a.slotId IN :slotIds AND a.status IN " + LIVE + """
             AND a.validFrom < :until AND (a.validUntil IS NULL OR a.validUntil > :from)
            """)
    List<ParkingAllocation> findOverlappingAny(@Param("slotIds") Collection<UUID> slotIds, @Param("from") Instant from,
                                               @Param("until") Instant until);

    @Query("SELECT a FROM ParkingAllocation a WHERE a.vehicleId = :vehicleId AND a.status IN " + LIVE
            + " ORDER BY a.validFrom DESC")
    List<ParkingAllocation> findLiveByVehicle(@Param("vehicleId") UUID vehicleId);

    @Query("SELECT a FROM ParkingAllocation a WHERE a.vehicleId IN :vehicleIds AND a.status IN " + LIVE
            + " ORDER BY a.validFrom DESC")
    List<ParkingAllocation> findLiveByVehicles(@Param("vehicleIds") Collection<UUID> vehicleIds);

    @Query("SELECT a FROM ParkingAllocation a WHERE a.flatId IN :flatIds ORDER BY a.validFrom DESC")
    List<ParkingAllocation> findByFlats(@Param("flatIds") Collection<UUID> flatIds);

    @Query("""
            SELECT a FROM ParkingAllocation a
            WHERE a.societyId = :societyId
              AND (:status IS NULL OR a.status = :status)
              AND (:type IS NULL OR a.allocationType = :type)
              AND (:flatId IS NULL OR a.flatId = :flatId)
              AND (:slotId IS NULL OR a.slotId = :slotId)
              AND (:vehicleId IS NULL OR a.vehicleId = :vehicleId)
            """)
    Page<ParkingAllocation> search(@Param("societyId") UUID societyId, @Param("status") AllocationStatus status,
                                   @Param("type") AllocationType type, @Param("flatId") UUID flatId,
                                   @Param("slotId") UUID slotId, @Param("vehicleId") UUID vehicleId, Pageable pageable);

    @Query("SELECT a.id FROM ParkingAllocation a WHERE a.status IN " + LIVE
            + " AND a.validUntil IS NOT NULL AND a.validUntil <= :now")
    List<UUID> findEndedIds(@Param("now") Instant now);
}
