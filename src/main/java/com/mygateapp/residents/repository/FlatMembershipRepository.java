package com.mygateapp.residents.repository;

import com.mygateapp.residents.entity.FlatMembership;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlatMembershipRepository extends JpaRepository<FlatMembership, UUID> {

    @Query("""
            SELECT m FROM FlatMembership m JOIN FETCH m.flat f JOIN FETCH f.tower LEFT JOIN FETCH f.wing JOIN FETCH m.user
            WHERE m.user.id = :userId AND m.status IN :statuses
            ORDER BY m.primary DESC, m.createdAt
            """)
    List<FlatMembership> findForUser(@Param("userId") UUID userId,
                                     @Param("statuses") Collection<FlatMembership.Status> statuses);

    @Query("""
            SELECT m FROM FlatMembership m JOIN FETCH m.user
            WHERE m.flat.id = :flatId ORDER BY m.createdAt DESC
            """)
    List<FlatMembership> findHistoryForFlat(@Param("flatId") UUID flatId);

    @Query("""
            SELECT COUNT(m) > 0 FROM FlatMembership m
            WHERE m.flat.id = :flatId AND m.user.id = :userId AND m.status IN :statuses
            """)
    boolean existsOpen(@Param("flatId") UUID flatId, @Param("userId") UUID userId,
                       @Param("statuses") Collection<FlatMembership.Status> statuses);

    long countByUserIdAndSocietyIdAndStatus(UUID userId, UUID societyId, FlatMembership.Status status);

    long countBySocietyIdAndStatus(UUID societyId, FlatMembership.Status status);

    @Query("""
            SELECT m.user.id FROM FlatMembership m
            WHERE m.flat.id IN :flatIds AND m.status = com.mygateapp.residents.entity.FlatMembership.Status.ACTIVE
              AND m.user.status = com.mygateapp.users.enums.UserStatus.ACTIVE
            """)
    List<UUID> findActiveResidentUserIds(@Param("flatIds") Collection<UUID> flatIds);

    @Query("""
            SELECT DISTINCT m.user.id FROM FlatMembership m
            WHERE m.flat.tower.id IN :towerIds AND m.status = com.mygateapp.residents.entity.FlatMembership.Status.ACTIVE
              AND m.user.status = com.mygateapp.users.enums.UserStatus.ACTIVE
            """)
    List<UUID> findActiveResidentUserIdsInTowers(@Param("towerIds") Collection<UUID> towerIds);

    @Query(value = """
            SELECT m FROM FlatMembership m JOIN m.flat f JOIN f.tower t JOIN m.user u
            WHERE m.societyId = :societyId
              AND (:status IS NULL OR m.status = :status)
              AND (:towerId IS NULL OR t.id = :towerId)
              AND (:flatId IS NULL OR f.id = :flatId)
              AND (:q IS NULL OR LOWER(u.name) LIKE :q ESCAPE '\\' OR u.mobile LIKE :q ESCAPE '\\'
                   OR LOWER(f.flatNumber) LIKE :q ESCAPE '\\')
            """, countQuery = """
            SELECT COUNT(m) FROM FlatMembership m JOIN m.flat f JOIN f.tower t JOIN m.user u
            WHERE m.societyId = :societyId
              AND (:status IS NULL OR m.status = :status)
              AND (:towerId IS NULL OR t.id = :towerId)
              AND (:flatId IS NULL OR f.id = :flatId)
              AND (:q IS NULL OR LOWER(u.name) LIKE :q ESCAPE '\\' OR u.mobile LIKE :q ESCAPE '\\'
                   OR LOWER(f.flatNumber) LIKE :q ESCAPE '\\')
            """)
    Page<FlatMembership> search(@Param("societyId") UUID societyId, @Param("status") FlatMembership.Status status,
                                @Param("towerId") UUID towerId, @Param("flatId") UUID flatId,
                                @Param("q") String likePattern, Pageable pageable);
}
