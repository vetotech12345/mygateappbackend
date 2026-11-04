package com.mygateapp.notifications.service;

import com.mygateapp.common.Pages;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.notifications.dto.NotificationResponse;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.notifications.entity.NotificationOutbox;
import com.mygateapp.notifications.enums.NotificationType;
import com.mygateapp.notifications.repository.NotificationRepository;
import com.mygateapp.notifications.repository.OutboxRepository;
import com.mygateapp.security.AccessService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates in-app notifications and their push jobs in the caller's transaction (transactional outbox). Push delivery
 * happens after commit in {@link NotificationDispatcher}, so a rolled-back business change never notifies anyone.
 */
@Service
public class NotificationService {

    /** Domain event carrying the outbox ids written in the committing transaction. */
    public record OutboxCreated(List<UUID> outboxIds) {
    }

    private final NotificationRepository notifications;
    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, OutboxRepository outbox, JdbcTemplate jdbc,
                               AccessService access, ApplicationEventPublisher events, Clock clock) {
        this.notifications = notifications;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyUsers(Collection<UUID> userIds, UUID societyId, NotificationType type, String title, String body,
                            Notification.ReferenceType referenceType, UUID referenceId) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<UUID> outboxIds = new ArrayList<>();
        for (UUID userId : new LinkedHashSet<>(userIds)) {
            Notification notification = new Notification();
            notification.setUserId(userId);
            notification.setSocietyId(societyId);
            notification.setType(type);
            notification.setTitle(truncate(title, 150));
            notification.setBody(truncate(body == null ? "" : body, 500));
            notification.setReferenceType(referenceType);
            notification.setReferenceId(referenceId);
            notification.setCreatedAt(now);
            notifications.save(notification);

            NotificationOutbox job = new NotificationOutbox();
            job.setNotificationId(notification.getId());
            job.setCreatedAt(now);
            job.setNextAttemptAt(now);
            if (!type.mandatory() && !isEnabled(userId, type)) {
                job.setStatus(NotificationOutbox.Status.SKIPPED);
                job.setLastError("Disabled in user preferences");
                job.setProcessedAt(now);
            }
            outbox.save(job);
            if (job.getStatus() == NotificationOutbox.Status.PENDING) {
                outboxIds.add(job.getId());
            }
        }
        if (!outboxIds.isEmpty()) {
            events.publishEvent(new OutboxCreated(outboxIds));
        }
    }

    // ----------------------------------------------------------------- user API

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(Integer page, Integer size) {
        UUID userId = access.currentUserId();
        return PageResponse.of(notifications.findByUserIdOrderByCreatedAtDesc(userId, Pages.of(page, size)),
                NotificationResponse::of);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return notifications.countByUserIdAndReadAtIsNull(access.currentUserId());
    }

    @Transactional
    public void markRead(UUID id) {
        Notification notification = notifications.findByIdAndUserId(id, access.currentUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOTIFICATION_NOT_FOUND));
        if (notification.getReadAt() == null) {
            notification.setReadAt(clock.instant());
        }
    }

    @Transactional
    public void markAllRead() {
        notifications.markAllRead(access.currentUserId(), clock.instant());
    }

    // ------------------------------------------------------------- preferences

    @Transactional(readOnly = true)
    public Map<NotificationType, Boolean> preferences() {
        return loadPreferences(access.currentUserId());
    }

    @Transactional
    public Map<NotificationType, Boolean> updatePreferences(Map<NotificationType, Boolean> changes) {
        UUID userId = access.currentUserId();
        changes.forEach((type, enabled) -> {
            if (type == null || enabled == null || type.mandatory()) {
                return;
            }
            jdbc.update("""
                    INSERT INTO notification_preferences (user_id, notification_type, enabled) VALUES (?, ?, ?)
                    ON CONFLICT (user_id, notification_type) DO UPDATE SET enabled = EXCLUDED.enabled
                    """, userId, type.name(), enabled);
        });
        return loadPreferences(userId);
    }

    private Map<NotificationType, Boolean> loadPreferences(UUID userId) {
        Map<NotificationType, Boolean> result = new EnumMap<>(NotificationType.class);
        for (NotificationType type : NotificationType.values()) {
            result.put(type, true);
        }
        jdbc.query("SELECT notification_type, enabled FROM notification_preferences WHERE user_id = ?", rs -> {
            try {
                NotificationType type = NotificationType.valueOf(rs.getString(1));
                result.put(type, type.mandatory() || rs.getBoolean(2));
            } catch (IllegalArgumentException ignored) {
                // a type removed from the enum
            }
        }, userId);
        return result;
    }

    private boolean isEnabled(UUID userId, NotificationType type) {
        List<Boolean> values = jdbc.queryForList(
                "SELECT enabled FROM notification_preferences WHERE user_id = ? AND notification_type = ?",
                Boolean.class, userId, type.name());
        return values.isEmpty() || Boolean.TRUE.equals(values.getFirst());
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
