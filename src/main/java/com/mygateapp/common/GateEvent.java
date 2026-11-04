package com.mygateapp.common;

import com.mygateapp.common.dto.Refs;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Append-only history row (visitor_events, delivery_events). The database rejects UPDATEs on these tables; the
 * entities are also {@code @Immutable}. Titles never contain the visitor's name so retention anonymisation does not
 * need to rewrite history.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class GateEvent extends IdentifiedEntity {

    @Column(name = "society_id", nullable = false, updatable = false)
    private UUID societyId;

    @Column(name = "event_type", nullable = false, length = 30, updatable = false)
    private String eventType;

    /** Status (Flutter display status) the subject moved to. */
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private String status;

    @Column(name = "title", nullable = false, length = 150, updatable = false)
    private String title;

    @Column(name = "description", length = 300, updatable = false)
    private String description;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "actor_name", length = 80, updatable = false)
    private String actorName;

    @Column(name = "gate_id", updatable = false)
    private UUID gateId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public Refs.TimelineEvent toTimeline() {
        return new Refs.TimelineEvent(title, occurredAt, actorName, status, description);
    }
}
