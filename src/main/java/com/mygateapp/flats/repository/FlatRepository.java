package com.mygateapp.flats.repository;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.flats.entity.Flat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlatRepository extends JpaRepository<Flat, UUID> {

    @Query("""
            SELECT f FROM Flat f JOIN FETCH f.tower t LEFT JOIN FETCH f.wing w
            WHERE f.societyId = :societyId AND f.status = :status
            ORDER BY t.code, w.name NULLS FIRST, f.floorNumber, f.flatNumber
            """)
    List<Flat> findAllForPicker(@Param("societyId") UUID societyId, @Param("status") ActiveStatus status);

    @Query(value = """
            SELECT f FROM Flat f JOIN f.tower t LEFT JOIN f.wing w
            WHERE f.societyId = :societyId
              AND (:towerId IS NULL OR t.id = :towerId)
              AND (:wingId IS NULL OR w.id = :wingId)
              AND (:status IS NULL OR f.status = :status)
              AND (:q IS NULL OR LOWER(f.flatNumber) LIKE :q ESCAPE '\\' OR LOWER(t.code) LIKE :q ESCAPE '\\')
            """)
    Page<Flat> search(@Param("societyId") UUID societyId, @Param("towerId") UUID towerId, @Param("wingId") UUID wingId,
                      @Param("status") ActiveStatus status, @Param("q") String likePattern, Pageable pageable);

    @Query("""
            SELECT COUNT(f) > 0 FROM Flat f
            WHERE f.tower.id = :towerId AND f.flatNumber = :number
              AND ((:wingId IS NULL AND f.wing IS NULL) OR f.wing.id = :wingId)
              AND (:excludeId IS NULL OR f.id <> :excludeId)
            """)
    boolean numberTaken(@Param("towerId") UUID towerId, @Param("wingId") UUID wingId, @Param("number") String number,
                        @Param("excludeId") UUID excludeId);

    /** All flat numbers (any status) of a tower, or of one wing of it. */
    @Query("""
            SELECT f.flatNumber FROM Flat f
            WHERE f.tower.id = :towerId AND ((:wingId IS NULL AND f.wing IS NULL) OR f.wing.id = :wingId)
            """)
    List<String> findNumbers(@Param("towerId") UUID towerId, @Param("wingId") UUID wingId);

    long countBySocietyId(UUID societyId);

    long countBySocietyIdAndStatus(UUID societyId, ActiveStatus status);
}
