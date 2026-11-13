package com.mygateapp.parking.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import com.mygateapp.parking.enums.ParkingEnums.SlotOperationalStatus;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Physical slot. Allocation (who may use it when), occupancy (an active session) and reservation are tracked
 * separately; only the operational status is stored here.
 */
@Entity
@Table(name = "parking_slots")
@Getter
@Setter
public class ParkingSlot extends VersionedEntity {

    @Column(name = "society_id", nullable = false, updatable = false)
    private UUID societyId;

    @Column(name = "area_id", nullable = false)
    private UUID areaId;

    @Column(name = "slot_number", nullable = false, length = 20)
    private String slotNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_type", nullable = false, length = 20)
    private ParkingUsage usage;

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 20)
    private SlotOperationalStatus operationalStatus = SlotOperationalStatus.ACTIVE;

    @Column(name = "out_of_service_reason", length = 200)
    private String outOfServiceReason;

    @Column(name = "notes", length = 200)
    private String notes;

    public boolean isInService() {
        return operationalStatus == SlotOperationalStatus.ACTIVE;
    }
}
