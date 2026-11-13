package com.mygateapp.parking.repository;

import com.mygateapp.parking.entity.ParkingSlot;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import com.mygateapp.parking.enums.ParkingEnums.SlotOperationalStatus;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import jakarta.persistence.LockModeType;
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

public interface ParkingSlotRepository extends JpaRepository<ParkingSlot, UUID> {

    /** Serialises allocation / reservation of one slot. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ParkingSlot s WHERE s.id = :id")
    Optional<ParkingSlot> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT s FROM ParkingSlot s
            WHERE s.societyId = :societyId
              AND (:areaId IS NULL OR s.areaId = :areaId)
              AND (:usage IS NULL OR s.usage = :usage)
              AND (:status IS NULL OR s.operationalStatus = :status)
              AND (:vehicleType IS NULL OR s.vehicleType = :vehicleType)
              AND (:q IS NULL OR UPPER(s.slotNumber) LIKE :q ESCAPE '\\')
            """)
    Page<ParkingSlot> search(@Param("societyId") UUID societyId, @Param("areaId") UUID areaId,
                             @Param("usage") ParkingUsage usage, @Param("status") SlotOperationalStatus status,
                             @Param("vehicleType") VehicleType vehicleType, @Param("q") String upperLikePattern,
                             Pageable pageable);

    /** In-service slots of active areas with one of the given usages, in a stable assignment order. */
    @Query("""
            SELECT s FROM ParkingSlot s, ParkingArea a
            WHERE a.id = s.areaId AND s.societyId = :societyId AND s.usage IN :usages
              AND s.operationalStatus = com.mygateapp.parking.enums.ParkingEnums.SlotOperationalStatus.ACTIVE
              AND a.status = com.mygateapp.common.ActiveStatus.ACTIVE
              AND (:areaId IS NULL OR s.areaId = :areaId)
            ORDER BY a.name, s.slotNumber
            """)
    List<ParkingSlot> findCandidates(@Param("societyId") UUID societyId, @Param("usages") Collection<ParkingUsage> usages,
                                     @Param("areaId") UUID areaId);

    List<ParkingSlot> findBySocietyId(UUID societyId);

    List<ParkingSlot> findByAreaId(UUID areaId);

    boolean existsByAreaIdAndSlotNumberIgnoreCase(UUID areaId, String slotNumber);

    /** {@code q} is a normalised term (no spaces, hyphens or dots); the slot number is compared the same way. */
    @Query("""
            SELECT s FROM ParkingSlot s
            WHERE s.societyId = :societyId
              AND REPLACE(REPLACE(REPLACE(UPPER(s.slotNumber), '-', ''), ' ', ''), '.', '') LIKE :q ESCAPE '\\'
            ORDER BY s.slotNumber
            """)
    List<ParkingSlot> searchByNumber(@Param("societyId") UUID societyId, @Param("q") String upperLikePattern,
                                     Pageable pageable);
}
