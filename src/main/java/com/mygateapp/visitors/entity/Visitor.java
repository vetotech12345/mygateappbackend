package com.mygateapp.visitors.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.common.gate.GateMovement;
import com.mygateapp.common.gate.MovementStatus;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.guards.entity.SocietyGate;
import com.mygateapp.users.entity.User;
import com.mygateapp.visitors.enums.VisitorEnums.ApprovalStatus;
import com.mygateapp.visitors.enums.VisitorEnums.DisplayStatus;
import com.mygateapp.visitors.enums.VisitorEnums.Source;
import com.mygateapp.visitors.enums.VisitorEnums.VisitorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One visit: either a walk-in request registered by a guard, or an invitation (pre-approval) created by a resident.
 * An invitation is single-use: the same row is used for entry and exit.
 */
@Entity
@Table(name = "visitors")
@Getter
@Setter
public class Visitor extends VersionedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flat_id", nullable = false)
    private Flat flat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gate_id")
    private SocietyGate gate;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private Source source;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "mobile", length = 15)
    private String mobile;

    @Enumerated(EnumType.STRING)
    @Column(name = "visitor_type", nullable = false, length = 20)
    private VisitorType visitorType;

    @Column(name = "purpose", nullable = false, length = 120)
    private String purpose;

    @Column(name = "vehicle_number", length = 13)
    private String vehicleNumber;

    @Column(name = "guest_count", nullable = false)
    private int guestCount = 1;

    @Column(name = "photo_file_id")
    private UUID photoFileId;

    @Column(name = "notes", length = 200)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private ApprovalStatus approvalStatus;

    @Embedded
    private GateMovement movement = new GateMovement();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "rejection_reason", length = 200)
    private String rejectionReason;

    @Column(name = "pass_code", length = 6)
    private String passCode;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    public boolean isInvitation() {
        return source == Source.INVITATION;
    }

    public boolean isWithinWindow(Instant at) {
        return validFrom != null && validUntil != null && !at.isBefore(validFrom) && !at.isAfter(validUntil);
    }

    public DisplayStatus displayStatus() {
        MovementStatus movementStatus = movement.getStatus();
        if (movementStatus == MovementStatus.INSIDE) {
            return DisplayStatus.CHECKED_IN;
        }
        if (movementStatus == MovementStatus.EXITED) {
            return DisplayStatus.CHECKED_OUT;
        }
        if (approvalStatus == ApprovalStatus.APPROVED && isInvitation()) {
            return DisplayStatus.PRE_APPROVED;
        }
        return DisplayStatus.valueOf(approvalStatus.name());
    }
}
