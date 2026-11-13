package com.mygateapp.parking.enums;

import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import java.util.EnumSet;
import java.util.Set;

/**
 * Parking enums. Wire values match the Flutter parking module (docs/PARKING_API_REQUIREMENTS.md in the app); see
 * docs/SOCIETY_PARKING_BACKEND_ANALYSIS.md §5.1 for the mapping to the master prompt's names.
 */
public final class ParkingEnums {

    private ParkingEnums() {
    }

    /** What an area or slot is meant for. {@code COMMON} = authorised common use, {@code RESERVED} = society-reserved. */
    public enum ParkingUsage {
        RESIDENT, VISITOR, PAID, SOCIETY, STAFF, SERVICE, EMERGENCY, COMMON, RESERVED;

        /** Slot usages that may serve a vehicle of {@code category}. Usage alone never authorises an allocation. */
        public static Set<ParkingUsage> servingCategory(Category category) {
            return switch (category) {
                case RESIDENT -> EnumSet.of(RESIDENT, COMMON);
                case VISITOR -> EnumSet.of(VISITOR, COMMON);
                case PAID -> EnumSet.of(PAID, COMMON);
                case SOCIETY -> EnumSet.of(SOCIETY, RESERVED);
                case STAFF -> EnumSet.of(STAFF, RESERVED);
                case SERVICE -> EnumSet.of(SERVICE, RESERVED);
                case EMERGENCY -> EnumSet.of(EMERGENCY, RESERVED);
            };
        }

        /** Slot usages that an availability query for this usage counts. */
        public Set<ParkingUsage> servedBy() {
            return switch (this) {
                case RESIDENT, VISITOR, PAID -> EnumSet.of(this, COMMON);
                case SOCIETY, STAFF, SERVICE, EMERGENCY -> EnumSet.of(this, RESERVED);
                case COMMON, RESERVED -> EnumSet.of(this);
            };
        }
    }

    public enum SlotOperationalStatus { ACTIVE, OUT_OF_SERVICE }

    /** Single display status for the guard slot map (Flutter {@code ParkingSlotStatus}). */
    public enum SlotStatus { AVAILABLE, OCCUPIED, RESERVED, BLOCKED }

    public enum AllocationType { PERMANENT, TEMPORARY, VISITOR, PAID, SOCIETY }

    public enum AllocationStatus {
        ACTIVE, PENDING, SUSPENDED, EXPIRED, REVOKED;

        /** Live allocations hold the slot (they take part in the overlap exclusion constraint). */
        public boolean live() {
            return this == ACTIVE || this == PENDING || this == SUSPENDED;
        }
    }

    public enum PermitType { RESIDENT, TEMPORARY, VISITOR, PAID_MONTHLY, SOCIETY, STAFF, SERVICE, EMERGENCY }

    public enum PermitStatus { ACTIVE, PENDING_PAYMENT, EXPIRED, REVOKED, SUSPENDED }

    public enum RequestType { ADDITIONAL_SLOT, TEMPORARY_PERMIT, VISITOR_SLOT, PAID_PERMIT }

    public enum RequestStatus { PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED }

    public enum VisitorParkingType { VISITOR, PAID }

    /** Stored session status. */
    public enum SessionStatus { ACTIVE, COMPLETED, CANCELLED }

    /** API session status: {@code OVERSTAY} = stored ACTIVE and past {@code validUntil} plus the grace period. */
    public enum SessionDisplayStatus { ACTIVE, OVERSTAY, COMPLETED, CANCELLED }

    /** Payment state of a session's charges (Flutter {@code ParkingPaymentStatus}). */
    public enum ChargeStatus { NOT_APPLICABLE, PENDING, PAID, WAIVED, FAILED }

    public enum VerificationDecision { ALLOWED, ALREADY_INSIDE, NEEDS_VISITOR_APPROVAL, NOT_REGISTERED, DENIED }

    public enum MovementType { ENTRY, ENTRY_OVERRIDE, EXIT, OVERSTAY, PAYMENT, CANCELLED }

    public enum SessionScope { MINE, ACTIVE, OVERSTAY, HISTORY }

    public enum SearchField { VEHICLE, FLAT, SLOT, PERMIT }

    public enum AlertType { PERMIT_EXPIRING, OVERSTAY, PAYMENT_DUE, REQUEST_UPDATE, VEHICLE_UPDATE, GENERAL }

    public enum AlertSeverity { INFO, WARNING, CRITICAL }
}
