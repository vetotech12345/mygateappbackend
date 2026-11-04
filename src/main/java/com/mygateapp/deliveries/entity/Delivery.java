package com.mygateapp.deliveries.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.common.gate.GateMovement;
import com.mygateapp.common.gate.MovementStatus;
import com.mygateapp.deliveries.enums.DeliveryCategory;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.guards.entity.SocietyGate;
import com.mygateapp.users.entity.User;
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

@Entity
@Table(name = "deliveries")
@Getter
@Setter
public class Delivery extends VersionedEntity {

    public enum ApprovalStatus { PENDING, APPROVED, REJECTED, LEFT_AT_GATE, CANCELLED }

    /** Flutter status, derived from approval + movement. */
    public enum DisplayStatus { PENDING, APPROVED, REJECTED, LEFT_AT_GATE, ENTERED, COMPLETED, CANCELLED }

    public enum Scope { PENDING, ACTIVE, HISTORY }

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flat_id", nullable = false)
    private Flat flat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gate_id", nullable = false)
    private SocietyGate gate;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private DeliveryCategory category;

    @Column(name = "company", nullable = false, length = 60)
    private String company;

    @Column(name = "agent_name", nullable = false, length = 60)
    private String agentName;

    @Column(name = "agent_mobile", length = 15)
    private String agentMobile;

    @Column(name = "package_count", nullable = false)
    private int packageCount = 1;

    @Column(name = "notes", length = 200)
    private String notes;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

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

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    public DisplayStatus displayStatus() {
        if (movement.getStatus() == MovementStatus.INSIDE) {
            return DisplayStatus.ENTERED;
        }
        if (movement.getStatus() == MovementStatus.EXITED) {
            return DisplayStatus.COMPLETED;
        }
        return DisplayStatus.valueOf(approvalStatus.name());
    }
}
