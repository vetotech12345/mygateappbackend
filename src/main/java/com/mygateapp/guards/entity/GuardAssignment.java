package com.mygateapp.guards.entity;

import com.mygateapp.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Gate and shift assignment of a guard. One active row per guard; previous rows are history. */
@Entity
@Table(name = "guard_assignments")
@Getter
@Setter
public class GuardAssignment extends AuditedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "guard_id", nullable = false)
    private UUID guardId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gate_id", nullable = false)
    private SocietyGate gate;

    @Column(name = "shift_name", length = 40)
    private String shiftName;

    @Column(name = "shift_start")
    private LocalTime shiftStart;

    @Column(name = "shift_end")
    private LocalTime shiftEnd;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    /** e.g. "Day shift · 08:00 – 20:00" (Flutter shows this string as-is). */
    public String shiftLabel() {
        StringBuilder label = new StringBuilder(shiftName == null ? "Shift" : shiftName);
        if (shiftStart != null && shiftEnd != null) {
            label.append(" · ").append(shiftStart).append(" – ").append(shiftEnd);
        }
        return label.toString();
    }
}
