package com.mygateapp.complaints.repository;

import com.mygateapp.complaints.entity.Complaint;
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

public interface ComplaintRepository extends JpaRepository<Complaint, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Complaint c WHERE c.id = :id")
    Optional<Complaint> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT c FROM Complaint c JOIN c.flat f
            WHERE c.societyId = :societyId
              AND (:restrictToFlats = false OR f.id IN :flatIds)
              AND (:status IS NULL OR c.status = :status)
              AND (:category IS NULL OR c.category = :category)
              AND (:flatId IS NULL OR f.id = :flatId)
              AND (:q IS NULL OR LOWER(c.title) LIKE :q ESCAPE '\\' OR LOWER(c.ticketNumber) LIKE :q ESCAPE '\\')
            """)
    Page<Complaint> search(@Param("societyId") UUID societyId, @Param("restrictToFlats") boolean restrictToFlats,
                           @Param("flatIds") Collection<UUID> flatIds, @Param("status") Complaint.Status status,
                           @Param("category") Complaint.Category category, @Param("flatId") UUID flatId,
                           @Param("q") String likePattern, Pageable pageable);

    @Query("""
            SELECT COUNT(c) FROM Complaint c
            WHERE c.flat.id IN :flatIds AND c.status IN (com.mygateapp.complaints.entity.Complaint.Status.OPEN,
                  com.mygateapp.complaints.entity.Complaint.Status.IN_PROGRESS)
            """)
    long countOpenForFlats(@Param("flatIds") Collection<UUID> flatIds);

    long countBySocietyIdAndStatus(UUID societyId, Complaint.Status status);
}
