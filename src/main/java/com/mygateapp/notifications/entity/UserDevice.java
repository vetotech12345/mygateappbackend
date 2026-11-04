package com.mygateapp.notifications.entity;

import com.mygateapp.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A push target (FCM registration token / installation id). A user can have several devices. */
@Entity
@Table(name = "user_devices")
@Getter
@Setter
public class UserDevice extends AuditedEntity {

    public enum Platform { ANDROID, IOS, WEB }

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token", nullable = false, length = 512)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 10)
    private Platform platform;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "deactivated_reason", length = 60)
    private String deactivatedReason;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
