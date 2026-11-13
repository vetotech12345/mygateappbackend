package com.mygateapp.parkingpayment.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.parkingpayment.enums.PaymentEnums.BillingUnit;
import com.mygateapp.parkingpayment.enums.PaymentEnums.RateAppliesTo;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Society pricing for one audience / vehicle type / billing unit (optionally one area). */
@Entity
@Table(name = "parking_rate_plans")
@Getter
@Setter
public class ParkingRatePlan extends VersionedEntity {

    @Column(name = "society_id", nullable = false, updatable = false)
    private UUID societyId;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "area_id")
    private UUID areaId;

    /** Null = every vehicle type. */
    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 20)
    private VehicleType vehicleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to", nullable = false, length = 20)
    private RateAppliesTo appliesTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_unit", nullable = false, length = 20)
    private BillingUnit billingUnit;

    @Column(name = "base_rate", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseRate;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "free_minutes", nullable = false)
    private int freeMinutes;

    @Column(name = "grace_period_minutes", nullable = false)
    private int gracePeriodMinutes;

    @Column(name = "daily_cap", precision = 12, scale = 2)
    private BigDecimal dailyCap;

    @Column(name = "overstay_hourly_rate", precision = 12, scale = 2)
    private BigDecimal overstayHourlyRate;

    @Column(name = "tax_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxPercent = BigDecimal.ZERO;

    @Column(name = "tax_label", length = 30)
    private String taxLabel;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_until")
    private Instant effectiveUntil;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by")
    private UUID createdBy;

    public boolean isEffectiveAt(Instant at) {
        return active && !at.isBefore(effectiveFrom) && (effectiveUntil == null || at.isBefore(effectiveUntil));
    }
}
