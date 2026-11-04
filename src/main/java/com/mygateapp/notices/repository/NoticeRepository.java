package com.mygateapp.notices.repository;

import com.mygateapp.notices.entity.Notice;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

    /**
     * Published notices visible to a member: audience ALL, RESIDENTS (if resident), GUARDS (if guard), or TOWERS
     * matching one of the resident's towers. {@code unreadOnly} filters by the user's read receipts.
     */
    @Query(value = """
            SELECT n FROM Notice n
            WHERE n.societyId = :societyId AND n.deletedAt IS NULL
              AND n.status = com.mygateapp.notices.entity.Notice.Status.PUBLISHED
              AND (n.audience = com.mygateapp.notices.entity.Notice.Audience.ALL
                   OR (:resident = true AND n.audience = com.mygateapp.notices.entity.Notice.Audience.RESIDENTS)
                   OR (:guard = true AND n.audience = com.mygateapp.notices.entity.Notice.Audience.GUARDS)
                   OR (:resident = true AND n.audience = com.mygateapp.notices.entity.Notice.Audience.TOWERS
                       AND EXISTS (SELECT 1 FROM Notice n2 JOIN n2.towerIds t WHERE n2 = n AND t IN :towerIds)))
              AND (:category IS NULL OR n.category = :category)
              AND (:unreadOnly = false OR NOT EXISTS (SELECT 1 FROM NoticeReadReceipt r
                   WHERE r.noticeId = n.id AND r.userId = :userId))
            ORDER BY n.publishedAt DESC
            """)
    Page<Notice> findVisible(@Param("societyId") UUID societyId, @Param("userId") UUID userId,
                             @Param("resident") boolean resident, @Param("guard") boolean guard,
                             @Param("towerIds") Collection<UUID> towerIds, @Param("category") Notice.Category category,
                             @Param("unreadOnly") boolean unreadOnly, Pageable pageable);

    /** Admin view: drafts and published, not deleted. */
    @Query(value = """
            SELECT n FROM Notice n
            WHERE n.societyId = :societyId AND n.deletedAt IS NULL
              AND (:status IS NULL OR n.status = :status)
              AND (:category IS NULL OR n.category = :category)
            ORDER BY n.createdAt DESC
            """)
    Page<Notice> findForAdmin(@Param("societyId") UUID societyId, @Param("status") Notice.Status status,
                              @Param("category") Notice.Category category, Pageable pageable);
}
