package com.mygateapp.common.gate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * Gate movement of a person (visitor, delivery agent), tracked independently from approval: an approved visitor can
 * still be outside. State changes only through {@link GateMovementPolicy}.
 */
@Embeddable
@Getter
public class GateMovement {

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_status", nullable = false, length = 20)
    private MovementStatus status = MovementStatus.NOT_ENTERED;

    @Column(name = "entry_at")
    private Instant entryAt;

    @Column(name = "entry_gate_id")
    private UUID entryGateId;

    @Column(name = "entry_recorded_by")
    private UUID entryRecordedBy;

    @Column(name = "exit_at")
    private Instant exitAt;

    @Column(name = "exit_gate_id")
    private UUID exitGateId;

    @Column(name = "exit_recorded_by")
    private UUID exitRecordedBy;

    void markEntered(Instant at, UUID gateId, UUID recordedBy) {
        this.status = MovementStatus.INSIDE;
        this.entryAt = at;
        this.entryGateId = gateId;
        this.entryRecordedBy = recordedBy;
    }

    void markExited(Instant at, UUID gateId, UUID recordedBy) {
        this.status = MovementStatus.EXITED;
        this.exitAt = at;
        this.exitGateId = gateId;
        this.exitRecordedBy = recordedBy;
    }

    public boolean isInside() {
        return status == MovementStatus.INSIDE;
    }

    public boolean hasEntered() {
        return status != MovementStatus.NOT_ENTERED;
    }
}
