package com.mygateapp.vehicle.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.RegistrationStatus;
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
 * A vehicle known to a society. Resident vehicles belong to a flat; society, staff, service and emergency vehicles
 * belong to the society. Registration (approval) never grants gate access by itself: entry needs a permit, an
 * approved visitor request or a paid session.
 */
@Entity
@Table(name = "vehicles")
@Getter
@Setter
public class Vehicle extends VersionedEntity {

    @Column(name = "society_id", nullable = false, updatable = false)
    private UUID societyId;

    /** Normalised: upper case letters and digits only. */
    @Column(name = "registration_number", nullable = false, length = 15)
    private String registrationNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private Category category;

    @Column(name = "make", length = 40)
    private String make;

    @Column(name = "model", length = 40)
    private String model;

    @Column(name = "color", length = 30)
    private String color;

    @Column(name = "flat_id")
    private UUID flatId;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "owner_name", length = 80)
    private String ownerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_status", nullable = false, length = 20)
    private RegistrationStatus registrationStatus;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "review_remarks", length = 200)
    private String reviewRemarks;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    public boolean isApproved() {
        return active && registrationStatus == RegistrationStatus.APPROVED;
    }
}
