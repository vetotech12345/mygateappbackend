package com.mygateapp.parking.repository;

import com.mygateapp.parking.entity.ParkingArea;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingAreaRepository extends JpaRepository<ParkingArea, UUID> {

    List<ParkingArea> findBySocietyIdOrderByName(UUID societyId);

    boolean existsBySocietyIdAndNameIgnoreCaseAndIdNot(UUID societyId, String name, UUID id);

    @Query("""
            SELECT COUNT(a) > 0 FROM ParkingArea a
            WHERE a.societyId = :societyId AND (UPPER(a.code) = UPPER(:code) OR LOWER(a.name) = LOWER(:name))
              AND (:excludeId IS NULL OR a.id <> :excludeId)
            """)
    boolean codeOrNameTaken(@Param("societyId") UUID societyId, @Param("code") String code, @Param("name") String name,
                            @Param("excludeId") UUID excludeId);
}
