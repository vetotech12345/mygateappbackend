package com.mygateapp.parking.entity;

import com.mygateapp.common.TextListConverter;
import com.mygateapp.vehicle.enums.VehicleEnums.NumberFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/** Society-specific parking rules. One row per society, created with these defaults on first use. */
@Entity
@Table(name = "society_parking_settings")
@Getter
@Setter
public class SocietyParkingSettings {

    @Id
    @Column(name = "society_id")
    private UUID societyId;

    @Column(name = "visitor_parking_enabled", nullable = false)
    private boolean visitorParkingEnabled = true;

    /** Visitor requests that fit an available visitor slot are approved and allocated immediately. */
    @Column(name = "visitor_parking_auto_approve", nullable = false)
    private boolean visitorParkingAutoApprove;

    @Column(name = "max_visitor_parking_hours", nullable = false)
    private int maxVisitorParkingHours = 12;

    @Column(name = "paid_parking_enabled", nullable = false)
    private boolean paidParkingEnabled;

    /** Vehicles with no link to the society (public) may park in paid slots. */
    @Column(name = "external_paid_parking_allowed", nullable = false)
    private boolean externalPaidParkingAllowed;

    /** A paid session no longer than this is free (drop-off / pick-up). */
    @Column(name = "free_grace_minutes", nullable = false)
    private int freeGraceMinutes = 15;

    @Column(name = "max_vehicles_per_flat", nullable = false)
    private int maxVehiclesPerFlat = 4;

    @Column(name = "max_temporary_permit_days", nullable = false)
    private int maxTemporaryPermitDays = 30;

    /** When false, approved resident vehicles may enter without a permit (explicit society choice). */
    @Column(name = "resident_entry_requires_permit", nullable = false)
    private boolean residentEntryRequiresPermit = true;

    /** When true, a vehicle with outstanding charges cannot exit (409 PAYMENT_PENDING). */
    @Column(name = "payment_required_before_exit", nullable = false)
    private boolean paymentRequiredBeforeExit = true;

    /** Allows guards to admit an unauthorised EMERGENCY vehicle with a reason (audited, admins notified). */
    @Column(name = "emergency_override_allowed", nullable = false)
    private boolean emergencyOverrideAllowed;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_number_format", nullable = false, length = 10)
    private NumberFormat vehicleNumberFormat = NumberFormat.INDIA;

    /** Empty = all vehicle types. */
    @Convert(converter = TextListConverter.class)
    @Column(name = "allowed_vehicle_types")
    private List<String> allowedVehicleTypes = new ArrayList<>();

    /** Empty = all manual methods. */
    @Convert(converter = TextListConverter.class)
    @Column(name = "accepted_payment_methods")
    private List<String> acceptedPaymentMethods = new ArrayList<>();

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Convert(converter = TextListConverter.class)
    @Column(name = "rules")
    private List<String> rules = new ArrayList<>();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
