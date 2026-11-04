package com.mygateapp.notifications.entity;

import com.mygateapp.common.IdentifiedEntity;
import com.mygateapp.notifications.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** In-app notification for one user. Push delivery is tracked separately in {@link NotificationOutbox}. */
@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification extends IdentifiedEntity {

    public enum ReferenceType { VISITOR, DELIVERY, STAFF, COMPLAINT, NOTICE }

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "society_id")
    private UUID societyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 30)
    private ReferenceType referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
