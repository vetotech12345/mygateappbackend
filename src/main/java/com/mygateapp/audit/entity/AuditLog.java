package com.mygateapp.audit.entity;

import com.mygateapp.common.IdentifiedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

/** Append-only record of a sensitive administrative action. UPDATEs are rejected by a database trigger. */
@Entity
@Immutable
@Table(name = "audit_logs")
@Getter
@Setter
public class AuditLog extends IdentifiedEntity {

    @Column(name = "society_id", updatable = false)
    private UUID societyId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "actor_name", length = 80, updatable = false)
    private String actorName;

    @Column(name = "action", nullable = false, length = 60, updatable = false)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 40, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @Column(name = "summary", nullable = false, length = 300, updatable = false)
    private String summary;

    /** JSON with the changed fields. Never contains secrets, OTPs or tokens. */
    @Column(name = "details", updatable = false)
    private String details;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}
