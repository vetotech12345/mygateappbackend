package com.mygateapp.parking.entity;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.TextListConverter;
import com.mygateapp.common.VersionedEntity;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A parking area of a society: "Basement 1", "Visitor parking", "Society reserved". */
@Entity
@Table(name = "parking_areas")
@Getter
@Setter
public class ParkingArea extends VersionedEntity {

    @Column(name = "society_id", nullable = false, updatable = false)
    private UUID societyId;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "level", length = 20)
    private String level;

    @Column(name = "description", length = 300)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_type", nullable = false, length = 20)
    private ParkingUsage usage;

    @Convert(converter = TextListConverter.class)
    @Column(name = "vehicle_types", nullable = false)
    private List<String> vehicleTypes = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ActiveStatus status = ActiveStatus.ACTIVE;

    public boolean isActive() {
        return status == ActiveStatus.ACTIVE;
    }

    public Set<VehicleType> allowedTypes() {
        Set<VehicleType> types = EnumSet.noneOf(VehicleType.class);
        vehicleTypes.forEach(t -> types.add(VehicleType.valueOf(t)));
        return types;
    }
}
