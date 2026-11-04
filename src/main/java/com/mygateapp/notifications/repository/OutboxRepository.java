package com.mygateapp.notifications.repository;

import com.mygateapp.notifications.entity.NotificationOutbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<NotificationOutbox, UUID> {

    /** Claims due jobs; SKIP LOCKED lets several instances dispatch in parallel without double sending. */
    @Query(value = """
            SELECT * FROM notification_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationOutbox> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "SELECT * FROM notification_outbox WHERE id = :id AND status = 'PENDING' FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<NotificationOutbox> lockPending(@Param("id") UUID id);

    Optional<NotificationOutbox> findByNotificationId(UUID notificationId);
}
