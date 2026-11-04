package com.mygateapp.domesticstaff.repository;

import com.mygateapp.domesticstaff.entity.StaffAttendance;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffAttendanceRepository extends JpaRepository<StaffAttendance, UUID> {

    @Query("SELECT a FROM StaffAttendance a WHERE a.staff.id = :staffId AND a.checkOutAt IS NULL")
    Optional<StaffAttendance> findOpen(@Param("staffId") UUID staffId);

    @Query(value = """
            SELECT a FROM StaffAttendance a JOIN FETCH a.staff s
            WHERE a.societyId = :societyId
              AND (:staffId IS NULL OR s.id = :staffId)
              AND a.checkInAt >= :from
              AND (:restrictToFlats = false OR EXISTS (SELECT 1 FROM StaffFlatAssignment x
                   WHERE x.staffId = s.id AND x.active = true AND x.flat.id IN :flatIds))
            ORDER BY a.checkInAt DESC
            """, countQuery = """
            SELECT COUNT(a) FROM StaffAttendance a JOIN a.staff s
            WHERE a.societyId = :societyId
              AND (:staffId IS NULL OR s.id = :staffId)
              AND a.checkInAt >= :from
              AND (:restrictToFlats = false OR EXISTS (SELECT 1 FROM StaffFlatAssignment x
                   WHERE x.staffId = s.id AND x.active = true AND x.flat.id IN :flatIds))
            """)
    Page<StaffAttendance> search(@Param("societyId") UUID societyId, @Param("staffId") UUID staffId,
                                 @Param("from") Instant from, @Param("restrictToFlats") boolean restrictToFlats,
                                 @Param("flatIds") Collection<UUID> flatIds, Pageable pageable);
}
