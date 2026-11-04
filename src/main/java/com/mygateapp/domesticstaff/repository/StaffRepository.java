package com.mygateapp.domesticstaff.repository;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.domesticstaff.entity.DomesticStaff;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffRepository extends JpaRepository<DomesticStaff, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM DomesticStaff s WHERE s.id = :id")
    Optional<DomesticStaff> findByIdForUpdate(@Param("id") UUID id);

    boolean existsBySocietyIdAndMobile(UUID societyId, String mobile);

    boolean existsBySocietyIdAndPassNumber(UUID societyId, String passNumber);

    /**
     * Society staff list. {@code flatIds} restricts to staff assigned to those flats (residents); pass
     * {@code restrictToFlats=false} for guards/admins.
     */
    @Query(value = """
            SELECT s FROM DomesticStaff s
            WHERE s.societyId = :societyId
              AND (:restrictToFlats = false OR EXISTS (SELECT 1 FROM StaffFlatAssignment a
                   WHERE a.staffId = s.id AND a.active = true AND a.flat.id IN :flatIds))
              AND (:status IS NULL OR s.status = :status)
              AND (:inside IS NULL OR s.inside = :inside)
              AND (:category IS NULL OR s.category = :category)
              AND (:q IS NULL OR LOWER(s.name) LIKE :q ESCAPE '\\' OR s.mobile LIKE :q ESCAPE '\\'
                   OR LOWER(s.passNumber) LIKE :q ESCAPE '\\')
            ORDER BY s.inside DESC, s.name ASC
            """)
    Page<DomesticStaff> search(@Param("societyId") UUID societyId, @Param("restrictToFlats") boolean restrictToFlats,
                               @Param("flatIds") Collection<UUID> flatIds, @Param("status") ActiveStatus status,
                               @Param("inside") Boolean inside, @Param("category") DomesticStaff.Category category,
                               @Param("q") String likePattern, Pageable pageable);

    long countBySocietyIdAndInsideTrue(UUID societyId);

    @Query("""
            SELECT COUNT(DISTINCT s.id) FROM DomesticStaff s, StaffFlatAssignment a
            WHERE a.staffId = s.id AND a.active = true AND a.flat.id IN :flatIds AND s.inside = true
            """)
    long countInsideForFlats(@Param("flatIds") Collection<UUID> flatIds);
}
