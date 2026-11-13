package com.mygateapp.parking.entity;

import com.mygateapp.common.IdentifiedEntity;
import com.mygateapp.parking.enums.ParkingEnums.MovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

/** Append-only gate movement event. The database rejects UPDATEs on this table. */
@Entity
@Immutable
@Table(name = "parking_movements")
@Getter
@Setter
public class ParkingMovement extends IdentifiedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "vehicle_number", nullable = false, length = 15)
    private String vehicleNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private MovementType eventType;

    @Column(name = "gate_id")
    private UUID gateId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_name", length = 80)
    private String actorName;

    @Column(name = "details", length = 300)
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
