package com.mygateapp.parking.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.parking.enums.ParkingEnums.RequestStatus;
import com.mygateapp.parking.enums.ParkingEnums.RequestType;
import com.mygateapp.parking.enums.ParkingEnums.VisitorParkingType;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * A resident's request: additional slot, temporary permit, paid monthly permit, or parking for a visitor
 * ({@code VISITOR_SLOT}). Approval allocates a slot and/or issues a permit; it never approves a visitor's entry.
 */
@Entity
@Table(name = "parking_requests")
@Getter
@Setter
public class ParkingRequest extends VersionedEntity {

    @Column(name = "society_id", nullable = false, updatable = false)
    private UUID societyId;

    @Column(name = "reference_number", nullable = false, length = 20, updatable = false)
    private String referenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20, updatable = false)
    private RequestType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status;

    @Column(name = "flat_id", nullable = false, updatable = false)
    private UUID flatId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "vehicle_number", nullable = false, length = 15)
    private String vehicleNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "preferred_area_id")
    private UUID preferredAreaId;

    @Column(name = "rate_plan_id")
    private UUID ratePlanId;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "visitor_id")
    private UUID visitorId;

    @Column(name = "visitor_name", length = 60)
    private String visitorName;

    @Column(name = "visitor_mobile", length = 15)
    private String visitorMobile;

    @Column(name = "visit_purpose", length = 120)
    private String visitPurpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "parking_type", length = 20)
    private VisitorParkingType parkingType;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_by_name", length = 80)
    private String decidedByName;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_remarks", length = 300)
    private String decisionRemarks;

    @Column(name = "auto_approved", nullable = false)
    private boolean autoApproved;

    @Column(name = "assigned_slot_id")
    private UUID assignedSlotId;

    @Column(name = "allocation_id")
    private UUID allocationId;

    @Column(name = "permit_id")
    private UUID permitId;

    /** Set when a gate session used this approved request (visitor requests are single-use). */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    public boolean isVisitorRequest() {
        return type == RequestType.VISITOR_SLOT;
    }
}
