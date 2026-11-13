package com.mygateapp.parking.entity;

import com.mygateapp.common.IdentifiedEntity;
import com.mygateapp.common.TextListConverter;
import com.mygateapp.parking.enums.ParkingEnums.VerificationDecision;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Result of a guard's vehicle check. Entry requires an unused, unexpired verification from the same guard and
 * society; it is consumed atomically when the session is created.
 */
@Entity
@Table(name = "parking_verifications")
@Getter
@Setter
public class ParkingVerification extends IdentifiedEntity {

    @Column(name = "society_id", nullable = false, updatable = false)
    private UUID societyId;

    @Column(name = "guard_user_id", nullable = false, updatable = false)
    private UUID guardUserId;

    @Column(name = "gate_id", nullable = false, updatable = false)
    private UUID gateId;

    @Column(name = "vehicle_number", nullable = false, length = 15, updatable = false)
    private String vehicleNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 20)
    private VehicleType vehicleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 30)
    private VerificationDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private Category category;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "visitor_id")
    private UUID visitorId;

    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "permit_id")
    private UUID permitId;

    @Column(name = "slot_id")
    private UUID slotId;

    @Column(name = "flat_id")
    private UUID flatId;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "paid_parking_offered", nullable = false)
    private boolean paidParkingOffered;

    @Column(name = "summary", length = 200)
    private String summary;

    @Convert(converter = TextListConverter.class)
    @Column(name = "reasons")
    private List<String> reasons = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;
}
