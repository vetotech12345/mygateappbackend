package com.mygateapp.vehicle.repository;

import com.mygateapp.vehicle.entity.Vehicle;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.RegistrationStatus;
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

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Vehicle v WHERE v.id = :id")
    Optional<Vehicle> findByIdForUpdate(@Param("id") UUID id);

    Optional<Vehicle> findBySocietyIdAndRegistrationNumberAndActiveTrue(UUID societyId, String registrationNumber);

    boolean existsBySocietyIdAndRegistrationNumberAndActiveTrue(UUID societyId, String registrationNumber);

    long countByFlatIdAndActiveTrue(UUID flatId);

    List<Vehicle> findByFlatIdInAndActiveTrue(Collection<UUID> flatIds);

    /**
     * Society vehicles, optionally restricted to flats ({@code restrict} = true). {@code q} is a LIKE pattern on the
     * normalised number, make, model or owner name.
     */
    @Query("""
            SELECT v FROM Vehicle v
            WHERE v.societyId = :societyId
              AND (:restrict = false OR v.flatId IN :flatIds)
              AND (:flatId IS NULL OR v.flatId = :flatId)
              AND (:category IS NULL OR v.category = :category)
              AND (:status IS NULL OR v.registrationStatus = :status)
              AND (:active IS NULL OR v.active = :active)
              AND (:q IS NULL OR v.registrationNumber LIKE :numberLike ESCAPE '\\'
                   OR LOWER(v.make) LIKE :q ESCAPE '\\' OR LOWER(v.model) LIKE :q ESCAPE '\\'
                   OR LOWER(v.ownerName) LIKE :q ESCAPE '\\')
            """)
    Page<Vehicle> search(@Param("societyId") UUID societyId, @Param("restrict") boolean restrict,
                         @Param("flatIds") Collection<UUID> flatIds, @Param("flatId") UUID flatId,
                         @Param("category") Category category, @Param("status") RegistrationStatus status,
                         @Param("active") Boolean active, @Param("q") String likePattern,
                         @Param("numberLike") String numberLike, Pageable pageable);

    /** Gate search by (part of) the normalised number. */
    @Query("""
            SELECT v FROM Vehicle v
            WHERE v.societyId = :societyId AND v.active = true AND v.registrationNumber LIKE :numberLike ESCAPE '\\'
              AND (:category IS NULL OR v.category = :category)
            ORDER BY v.registrationNumber
            """)
    List<Vehicle> searchByNumber(@Param("societyId") UUID societyId, @Param("numberLike") String numberLike,
                                 @Param("category") Category category, Pageable pageable);

    @Query("""
            SELECT v FROM Vehicle v
            WHERE v.societyId = :societyId AND v.active = true AND v.flatId IN :flatIds
              AND (:category IS NULL OR v.category = :category)
            ORDER BY v.registrationNumber
            """)
    List<Vehicle> findActiveByFlats(@Param("societyId") UUID societyId, @Param("flatIds") Collection<UUID> flatIds,
                                    @Param("category") Category category, Pageable pageable);

    long countBySocietyIdAndActiveTrueAndRegistrationStatus(UUID societyId, RegistrationStatus status);

    long countBySocietyIdAndActiveTrue(UUID societyId);
}
