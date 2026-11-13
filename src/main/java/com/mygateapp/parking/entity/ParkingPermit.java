package com.mygateapp.parking.entity;

import com.mygateapp.common.TextListConverter;
import com.mygateapp.common.VersionedEntity;
import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.enums.ParkingEnums.PermitType;
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

/** Digital parking permit: the authorisation the gate checks for a vehicle number. */
@Entity
@Table(name = "parking_permits")
@Getter
@Setter
public class ParkingPermit extends VersionedEntity {

    @Column(name = "society_id", nullable = false, updatable = false)
    private UUID societyId;

    @Column(name = "permit_number", nullable = false, length = 30, updatable = false)
    private String permitNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "permit_type", nullable = false, length = 20)
    private PermitType permitType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PermitStatus status;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "vehicle_number", nullable = false, length = 15)
    private String vehicleNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "holder_name", length = 80)
    private String holderName;

    @Column(name = "flat_id")
    private UUID flatId;

    @Column(name = "slot_id")
    private UUID slotId;

    @Column(name = "area_id")
    private UUID areaId;

    @Column(name = "allocation_id")
    private UUID allocationId;

    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Convert(converter = TextListConverter.class)
    @Column(name = "conditions")
    private List<String> conditions = new ArrayList<>();

    @Column(name = "issued_by")
    private UUID issuedBy;

    @Column(name = "issued_by_name", length = 80)
    private String issuedByName;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoke_reason", length = 200)
    private String revokeReason;

    @Column(name = "expiry_reminded_at")
    private Instant expiryRemindedAt;

    public boolean isValidAt(Instant at) {
        return status == PermitStatus.ACTIVE && !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil));
    }
}
