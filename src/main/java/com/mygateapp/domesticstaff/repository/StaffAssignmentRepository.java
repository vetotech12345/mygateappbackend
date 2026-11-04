package com.mygateapp.domesticstaff.repository;

import com.mygateapp.domesticstaff.entity.StaffFlatAssignment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffAssignmentRepository extends JpaRepository<StaffFlatAssignment, UUID> {

    @Query("""
            SELECT a FROM StaffFlatAssignment a JOIN FETCH a.flat f JOIN FETCH f.tower LEFT JOIN FETCH f.wing
            WHERE a.staffId IN :staffIds AND a.active = true
            """)
    List<StaffFlatAssignment> findActiveForStaff(@Param("staffIds") Collection<UUID> staffIds);

    @Query("SELECT COUNT(a) > 0 FROM StaffFlatAssignment a WHERE a.staffId = :staffId AND a.active = true AND a.flat.id IN :flatIds")
    boolean isAssignedToAny(@Param("staffId") UUID staffId, @Param("flatIds") Collection<UUID> flatIds);
}
