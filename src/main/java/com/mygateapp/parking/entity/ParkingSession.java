package com.mygateapp.parking.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.parking.enums.ParkingEnums.ChargeStatus;
import com.mygateapp.parking.enums.ParkingEnums.SessionDisplayStatus;
import com.mygateapp.parking.enums.ParkingEnums.SessionStatus;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** One parked stay of a vehicle, from the guard-recorded entry to the exit. Server timestamps only. */
@Entity
@Table(name = "parking_sessions")
@Getter
@Setter
public class ParkingSession extends VersionedEntity {

    @Column(name = "society_id", nullable = false, updatable = false)
    private UUID societyId;

    @Column(name = "vehicle_number", nullable = false, length = 15, updatable = false)
    private String vehicleNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private Category category;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "visitor_id")
    private UUID visitorId;

    @Column(name = "flat_id")
    private UUID flatId;

    @Column(name = "slot_id")
    private UUID slotId;

    @Column(name = "permit_id")
    private UUID permitId;

    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "allocation_id")
    private UUID allocationId;

    @Column(name = "rate_plan_id")
    private UUID ratePlanId;

    @Column(name = "verification_id", updatable = false)
    private UUID verificationId;

    /** JSON copy of the rate plan at entry: later rate changes never alter this session's charge. */
    @Column(name = "rate_snapshot")
    private String rateSnapshot;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SessionStatus status = SessionStatus.ACTIVE;

    @Column(name = "entry_gate_id", nullable = false, updatable = false)
    private UUID entryGateId;

    @Column(name = "entry_at", nullable = false, updatable = false)
    private Instant entryAt;

    @Column(name = "entry_recorded_by", nullable = false, updatable = false)
    private UUID entryRecordedBy;

    @Column(name = "exit_gate_id")
    private UUID exitGateId;

    @Column(name = "exit_at")
    private Instant exitAt;

    @Column(name = "exit_recorded_by")
    private UUID exitRecordedBy;

    /** End of the authorised window (visitor request, temporary permit); null = open-ended. */
    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "overstay_notified_at")
    private Instant overstayNotifiedAt;

    @Column(name = "final_amount", precision = 12, scale = 2)
    private BigDecimal finalAmount;

    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO.setScale(2);

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private ChargeStatus paymentStatus = ChargeStatus.NOT_APPLICABLE;

    @Column(name = "paid_until")
    private Instant paidUntil;

    @Column(name = "emergency_override", nullable = false, updatable = false)
    private boolean emergencyOverride;

    @Column(name = "override_reason", length = 200, updatable = false)
    private String overrideReason;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    public boolean isActive() {
        return status == SessionStatus.ACTIVE;
    }

    public boolean isPaid() {
        return rateSnapshot != null;
    }

    public boolean isOverstay(Instant now, int graceMinutes) {
        return isActive() && validUntil != null && now.isAfter(validUntil.plus(Duration.ofMinutes(graceMinutes)));
    }

    public SessionDisplayStatus displayStatus(Instant now, int graceMinutes) {
        return switch (status) {
            case ACTIVE -> isOverstay(now, graceMinutes) ? SessionDisplayStatus.OVERSTAY : SessionDisplayStatus.ACTIVE;
            case COMPLETED -> SessionDisplayStatus.COMPLETED;
            case CANCELLED -> SessionDisplayStatus.CANCELLED;
        };
    }
}
