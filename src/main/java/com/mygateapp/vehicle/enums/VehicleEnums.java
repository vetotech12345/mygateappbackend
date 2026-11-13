package com.mygateapp.vehicle.enums;

import java.util.EnumSet;
import java.util.Set;

/** Vehicle enums. Wire values match the Flutter {@code VehicleType}, {@code VehicleCategory} and registration status. */
public final class VehicleEnums {

    private VehicleEnums() {
    }

    public enum VehicleType {
        CAR, BIKE, SCOOTER, OTHER;

        public boolean isTwoWheeler() {
            return this == BIKE || this == SCOOTER;
        }

        /** Vehicle types a slot (or rate) of this type accepts: two-wheelers share two-wheeler slots. */
        public Set<VehicleType> accepts() {
            return isTwoWheeler() ? EnumSet.of(BIKE, SCOOTER) : EnumSet.of(this);
        }

        public boolean fits(VehicleType slotOrRateType) {
            return slotOrRateType == null || slotOrRateType.accepts().contains(this);
        }
    }

    /** Who a vehicle belongs to. Superset of the master prompt's categories (adds STAFF and EMERGENCY). */
    public enum Category { RESIDENT, VISITOR, PAID, SOCIETY, STAFF, SERVICE, EMERGENCY }

    /**
     * Society-office review. NOT_SUBMITTED (saved by the resident) → PENDING → APPROVED / REJECTED; an approved vehicle
     * can be SUSPENDED. This is also the vehicle's verification status.
     */
    public enum RegistrationStatus { NOT_SUBMITTED, PENDING, APPROVED, REJECTED, SUSPENDED }

    /** Registration-number formats a society accepts. */
    public enum NumberFormat { INDIA, GENERIC }
}
