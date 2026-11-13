package com.mygateapp.parking.repository;

import com.mygateapp.parking.entity.ParkingSession;
import com.mygateapp.parking.enums.ParkingEnums.SessionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingSessionRepository extends JpaRepository<ParkingSession, UUID>,
        JpaSpecificationExecutor<ParkingSession> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ParkingSession s WHERE s.id = :id")
    Optional<ParkingSession> findByIdForUpdate(@Param("id") UUID id);

    Optional<ParkingSession> findBySocietyIdAndVehicleNumberAndStatus(UUID societyId, String vehicleNumber,
                                                                      SessionStatus status);

    @Query("""
            SELECT s FROM ParkingSession s
            WHERE s.slotId IN :slotIds AND s.status = com.mygateapp.parking.enums.ParkingEnums.SessionStatus.ACTIVE
            """)
    List<ParkingSession> findActiveInSlots(@Param("slotIds") Collection<UUID> slotIds);

    @Query("""
            SELECT s FROM ParkingSession s
            WHERE s.societyId = :societyId AND s.vehicleNumber IN :numbers
              AND s.status = com.mygateapp.parking.enums.ParkingEnums.SessionStatus.ACTIVE
            """)
    List<ParkingSession> findActiveByNumbers(@Param("societyId") UUID societyId,
                                             @Param("numbers") Collection<String> numbers);

    long countBySocietyIdAndStatus(UUID societyId, SessionStatus status);

    @Query("""
            SELECT COUNT(s) FROM ParkingSession s
            WHERE s.societyId = :societyId AND s.status = com.mygateapp.parking.enums.ParkingEnums.SessionStatus.ACTIVE
              AND s.rateSnapshot IS NOT NULL
            """)
    long countActivePaid(@Param("societyId") UUID societyId);

    @Query("""
            SELECT COUNT(s) FROM ParkingSession s
            WHERE s.societyId = :societyId AND s.status = com.mygateapp.parking.enums.ParkingEnums.SessionStatus.ACTIVE
              AND s.validUntil IS NOT NULL AND s.validUntil < :threshold
            """)
    long countOverstaying(@Param("societyId") UUID societyId, @Param("threshold") Instant threshold);

    @Query("SELECT COUNT(s) FROM ParkingSession s WHERE s.societyId = :societyId AND s.entryAt >= :from AND s.entryAt < :to")
    long countEntries(@Param("societyId") UUID societyId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT COUNT(s) FROM ParkingSession s WHERE s.societyId = :societyId AND s.exitAt >= :from AND s.exitAt < :to")
    long countExits(@Param("societyId") UUID societyId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            SELECT COUNT(s) FROM ParkingSession s
            WHERE s.flatId IN :flatIds AND s.status = com.mygateapp.parking.enums.ParkingEnums.SessionStatus.ACTIVE
              AND s.category = com.mygateapp.vehicle.enums.VehicleEnums.Category.VISITOR
            """)
    long countVisitorVehiclesInside(@Param("flatIds") Collection<UUID> flatIds);

    /** Active sessions past their window (plus grace) whose overstay has not been reported yet. */
    @Query("""
            SELECT s.id FROM ParkingSession s
            WHERE s.status = com.mygateapp.parking.enums.ParkingEnums.SessionStatus.ACTIVE
              AND s.validUntil IS NOT NULL AND s.validUntil < :threshold AND s.overstayNotifiedAt IS NULL
            """)
    List<UUID> findUnreportedOverstayIds(@Param("threshold") Instant threshold);

    @Query("""
            SELECT s FROM ParkingSession s
            WHERE s.flatId IN :flatIds AND s.status = com.mygateapp.parking.enums.ParkingEnums.SessionStatus.ACTIVE
              AND s.validUntil IS NOT NULL AND s.validUntil < :threshold
            """)
    List<ParkingSession> findOverstayingForFlats(@Param("flatIds") Collection<UUID> flatIds,
                                                 @Param("threshold") Instant threshold);

    @Query("SELECT s FROM ParkingSession s WHERE s.societyId = :societyId ORDER BY s.updatedAt DESC")
    List<ParkingSession> findRecent(@Param("societyId") UUID societyId, Pageable pageable);
}
