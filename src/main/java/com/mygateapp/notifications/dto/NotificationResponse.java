package com.mygateapp.notifications.dto;

import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.notifications.enums.NotificationType;
import java.time.Instant;
import java.util.UUID;

/** Matches the Flutter {@code NotificationDto}. */
public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String body,
        Instant createdAt,
        boolean read,
        UUID referenceId,
        Notification.ReferenceType referenceType) {

    public static NotificationResponse of(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getCreatedAt(),
                n.getReadAt() != null, n.getReferenceId(), n.getReferenceType());
    }
}
