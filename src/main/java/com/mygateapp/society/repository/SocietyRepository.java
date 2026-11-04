package com.mygateapp.society.repository;

import com.mygateapp.society.entity.Society;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocietyRepository extends JpaRepository<Society, UUID> {

    boolean existsByCode(String code);

    long countByStatus(Society.Status status);

    @Query("""
            SELECT s FROM Society s
            WHERE (:status IS NULL OR s.status = :status)
              AND (:q IS NULL OR LOWER(s.name) LIKE :q ESCAPE '\\' OR LOWER(s.code) LIKE :q ESCAPE '\\'
                   OR LOWER(s.city) LIKE :q ESCAPE '\\')
            """)
    Page<Society> search(@Param("status") Society.Status status, @Param("q") String likePattern, Pageable pageable);

    @Query("""
            SELECT s FROM Society s
            WHERE s.id IN :ids AND (:status IS NULL OR s.status = :status)
              AND (:q IS NULL OR LOWER(s.name) LIKE :q ESCAPE '\\' OR LOWER(s.code) LIKE :q ESCAPE '\\'
                   OR LOWER(s.city) LIKE :q ESCAPE '\\')
            """)
    Page<Society> searchWithin(@Param("ids") java.util.Collection<UUID> ids, @Param("status") Society.Status status,
                               @Param("q") String likePattern, Pageable pageable);
}
