package com.mygateapp.parking.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.dto.Refs.FlatRef;
import com.mygateapp.common.dto.Refs.GateRef;
import com.mygateapp.common.dto.Refs.TimelineEvent;
import com.mygateapp.parking.dto.ParkingRefs.AreaRef;
import com.mygateapp.parking.dto.ParkingRefs.Money;
import com.mygateapp.parking.dto.ParkingRefs.PermitRef;
import com.mygateapp.parking.dto.ParkingRefs.SlotRef;
import com.mygateapp.parking.dto.ParkingRefs.VehicleRef;
import com.mygateapp.parking.enums.ParkingEnums.AlertSeverity;
import com.mygateapp.parking.enums.ParkingEnums.AlertType;
import com.mygateapp.parking.enums.ParkingEnums.AllocationStatus;
import com.mygateapp.parking.enums.ParkingEnums.AllocationType;
import com.mygateapp.parking.enums.ParkingEnums.ChargeStatus;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.enums.ParkingEnums.PermitType;
import com.mygateapp.parking.enums.ParkingEnums.RequestStatus;
import com.mygateapp.parking.enums.ParkingEnums.RequestType;
import com.mygateapp.parking.enums.ParkingEnums.SessionDisplayStatus;
import com.mygateapp.parking.enums.ParkingEnums.SlotOperationalStatus;
import com.mygateapp.parking.enums.ParkingEnums.SlotStatus;
import com.mygateapp.parking.enums.ParkingEnums.VerificationDecision;
import com.mygateapp.parking.enums.ParkingEnums.VisitorParkingType;
import com.mygateapp.parkingpayment.dto.PaymentDtos.QuoteResponse;
import com.mygateapp.parkingpayment.dto.PaymentDtos.RateResponse;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentMethod;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.NumberFormat;
import com.mygateapp.vehicle.enums.VehicleEnums.RegistrationStatus;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import com.mygateapp.visitors.enums.VisitorEnums.DisplayStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request and response bodies of the parking API. Field names follow the Flutter parking DTOs. */
public final class ParkingDtos {

    private ParkingDtos() {
    }

    // ------------------------------------------------------------- settings

    /** {@code PUT /societies/{id}/parking/settings}. Every field is required (full replacement). */
    public record ParkingSettingsRequest(
            @NotNull Boolean visitorParkingEnabled,
            @NotNull Boolean visitorParkingAutoApprove,
            @NotNull @Min(1) @Max(168) Integer maxVisitorParkingHours,
            @NotNull Boolean paidParkingEnabled,
            @NotNull Boolean externalPaidParkingAllowed,
            @Schema(description = "A paid session no longer than this is free") @NotNull @Min(0) @Max(240) Integer freeGraceMinutes,
            @NotNull @Min(1) @Max(20) Integer maxVehiclesPerFlat,
            @NotNull @Min(1) @Max(365) Integer maxTemporaryPermitDays,
            @Schema(description = "false = approved resident vehicles may enter without a permit") @NotNull Boolean residentEntryRequiresPermit,
            @NotNull Boolean paymentRequiredBeforeExit,
            @Schema(description = "Guards may admit an unauthorised EMERGENCY vehicle with a reason (audited)") @NotNull Boolean emergencyOverrideAllowed,
            @NotNull NumberFormat vehicleNumberFormat,
            @Schema(description = "Empty = all types") Set<VehicleType> allowedVehicleTypes,
            @Schema(description = "Manual methods accepted. Empty = all manual methods") Set<PaymentMethod> acceptedPaymentMethods,
            @Pattern(regexp = "^[A-Z]{3}$", message = "Use an ISO 4217 currency code such as INR") String currency,
            @Size(max = 30) List<@NotBlank @Size(max = 200) String> rules) {
    }

    public record Capacity(int totalSlots, int residentSlots, int visitorSlots, int paidSlots, int reservedSlots,
                           int outOfServiceSlots) {
    }

    public record ParkingSettingsResponse(
            UUID societyId, boolean visitorParkingEnabled, boolean visitorParkingAutoApprove, int maxVisitorParkingHours,
            boolean paidParkingEnabled, boolean externalPaidParkingAllowed, int freeGraceMinutes, int maxVehiclesPerFlat,
            int maxTemporaryPermitDays, boolean residentEntryRequiresPermit, boolean paymentRequiredBeforeExit,
            boolean emergencyOverrideAllowed, NumberFormat vehicleNumberFormat, List<VehicleType> allowedVehicleTypes,
            List<PaymentMethod> acceptedPaymentMethods, String currency, List<String> rules,
            @Schema(description = "Always false until an online payment provider is configured") boolean onlinePaymentEnabled,
            @Schema(description = "Derived from the slot inventory (read-only)") Capacity capacity, Instant updatedAt) {
    }

    /** {@code GET /parking/config}: what residents and guards need (Flutter {@code ParkingConfig}). */
    public record ParkingConfigResponse(
            boolean visitorParkingEnabled, boolean paidParkingEnabled, boolean onlinePaymentEnabled,
            boolean visitorParkingAutoApprove, int maxVisitorParkingHours, int maxVehiclesPerFlat,
            int maxTemporaryPermitDays, NumberFormat vehicleNumberFormat, List<VehicleType> allowedVehicleTypes,
            List<PaymentMethod> acceptedPaymentMethods, String currency, List<String> rules, List<AreaResponse> areas) {
    }

    // ---------------------------------------------------------------- areas

    public record CreateAreaRequest(
            @Schema(example = "B1") @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,20}$", message = "Code: 1-20 letters, digits or hyphens") String code,
            @Schema(example = "Basement 1") @NotBlank @Size(max = 60) String name,
            @Schema(example = "B1") @Size(max = 20) String level,
            @Size(max = 300) String description,
            @NotNull ParkingUsage usage,
            @NotEmpty(message = "Choose at least one vehicle type") Set<VehicleType> vehicleTypes) {
    }

    public record UpdateAreaRequest(
            @Size(min = 1, max = 60) String name,
            @Size(max = 20) String level,
            @Size(max = 300) String description,
            ParkingUsage usage,
            Set<VehicleType> vehicleTypes,
            ActiveStatus status) {
    }

    /** {@code notes} = description (Flutter field name). Counts are live. */
    public record AreaResponse(
            UUID id, String code, String name, String level, ParkingUsage usage, List<VehicleType> vehicleTypes,
            int totalSlots, int availableSlots, int occupiedSlots, int outOfServiceSlots, boolean reserved,
            String notes, ActiveStatus status, Instant createdAt, Instant updatedAt) {
    }

    // ---------------------------------------------------------------- slots

    public record CreateSlotRequest(
            @NotNull UUID areaId,
            @Schema(example = "B1-042") @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,20}$", message = "Slot number: 1-20 letters, digits or hyphens") String number,
            @NotNull VehicleType vehicleType,
            @Schema(description = "Defaults to the area's usage") ParkingUsage usage,
            @Size(max = 200) String notes) {
    }

    @Schema(description = "Creates slots {prefix}{from..to}, e.g. prefix B1- from 1 to 40 padding 3 → B1-001..B1-040")
    public record BulkCreateSlotsRequest(
            @NotNull UUID areaId,
            @NotNull @Pattern(regexp = "^[A-Za-z0-9-]{0,10}$") String prefix,
            @NotNull @Min(0) @Max(9999) Integer from,
            @NotNull @Min(0) @Max(9999) Integer to,
            @Min(1) @Max(4) Integer padding,
            @NotNull VehicleType vehicleType,
            ParkingUsage usage) {
    }

    public record UpdateSlotRequest(
            @Pattern(regexp = "^[A-Za-z0-9-]{1,20}$") String number,
            VehicleType vehicleType,
            ParkingUsage usage,
            SlotOperationalStatus operationalStatus,
            @Size(max = 200) String outOfServiceReason,
            @Size(max = 200) String notes) {
    }

    public record AllocationSummary(UUID id, AllocationType allocationType, AllocationStatus status, FlatRef flat,
                                    String vehicleNumber, Instant validFrom, Instant validUntil) {
    }

    /**
     * {@code status} combines the four independent dimensions for the guard slot map; {@code allocated},
     * {@code occupied}, {@code reserved} and {@code operationalStatus} report them separately (an allocated slot is
     * not assumed to be occupied).
     */
    public record SlotResponse(
            UUID id, String number, AreaRef area, List<VehicleType> vehicleTypes, VehicleType vehicleType,
            ParkingUsage usage, SlotStatus status, SlotOperationalStatus operationalStatus, boolean allocated,
            boolean occupied, boolean reserved, String occupiedBy, AllocationSummary currentAllocation,
            String outOfServiceReason, String notes, Instant createdAt, Instant updatedAt) {
    }

    public record AreaAvailability(AreaRef area, int available, int total) {
    }

    public record AvailabilityResponse(
            ParkingUsage usage, VehicleType vehicleType, Instant from, Instant until, int availableSlots,
            int totalSlots, List<AreaAvailability> areas, Instant nextAvailableAt, boolean paidAlternativeAvailable) {
    }

    // ----------------------------------------------------------- allocations

    public record CreateAllocationRequest(
            @NotNull UUID slotId,
            @NotNull AllocationType allocationType,
            UUID flatId,
            UUID vehicleId,
            @Schema(description = "Defaults to now") Instant validFrom,
            @Schema(description = "Null = permanent / open-ended") Instant validUntil,
            @Size(max = 300) String notes,
            @Schema(description = "Issue a permit for the vehicle (default true when a vehicle is given)") Boolean issuePermit) {
    }

    @Schema(description = "status: REVOKED (cancel), SUSPENDED, or ACTIVE (resume a suspended allocation)")
    public record UpdateAllocationRequest(
            Instant validUntil,
            UUID vehicleId,
            AllocationStatus status,
            @Size(max = 200) String reason,
            @Size(max = 300) String notes) {
    }

    public record TransferAllocationRequest(
            @NotNull UUID slotId,
            UUID flatId,
            UUID vehicleId,
            @Size(max = 200) String reason) {
    }

    public record AllocationResponse(
            UUID id, SlotRef slot, AllocationType allocationType, AllocationStatus status, FlatRef flat,
            VehicleRef vehicle, Instant validFrom, Instant validUntil, PermitRef permit, UUID requestId, String notes,
            String endReason, Instant createdAt) {
    }

    // --------------------------------------------------------------- permits

    public record IssuePermitRequest(
            @NotNull PermitType type,
            @Schema(description = "Registered vehicle; otherwise vehicleNumber + vehicleType") UUID vehicleId,
            String vehicleNumber,
            VehicleType vehicleType,
            UUID flatId,
            UUID slotId,
            UUID areaId,
            @Size(max = 80) String holderName,
            Instant validFrom,
            Instant validUntil,
            @Size(max = 10) List<@NotBlank @Size(max = 200) String> conditions) {
    }

    public record PermitResponse(
            UUID id, String permitNumber, PermitType type, PermitStatus status, String holderName, String vehicleNumber,
            VehicleType vehicleType, UUID vehicleId, FlatRef flat, SlotRef slot, AreaRef area, Instant validFrom,
            Instant validUntil, String issuedBy, List<String> conditions, Instant revokedAt, String revokeReason) {
    }

    // -------------------------------------------------------------- requests

    @Schema(description = "ADDITIONAL_SLOT and PAID_PERMIT need an approved vehicle of the flat (vehicleId); "
            + "TEMPORARY_PERMIT takes vehicleNumber + vehicleType and a window; PAID_PERMIT needs a MONTHLY rateId")
    public record CreateRequestRequest(
            @NotNull RequestType type,
            @NotNull UUID flatId,
            UUID vehicleId,
            String vehicleNumber,
            VehicleType vehicleType,
            UUID preferredAreaId,
            UUID rateId,
            Instant validFrom,
            Instant validUntil,
            @Size(max = 300) String reason) {
    }

    public record CreateVisitorParkingRequest(
            @NotNull UUID flatId,
            @Schema(description = "Links an existing visitor of the same flat") UUID visitorId,
            @Size(max = 60) String visitorName,
            String visitorMobile,
            @NotBlank(message = "Enter the vehicle number") String vehicleNumber,
            @NotNull VehicleType vehicleType,
            @NotNull Instant expectedArrival,
            @NotNull Instant expectedDeparture,
            @Size(max = 120) String purpose,
            @Schema(description = "VISITOR (default) or PAID") VisitorParkingType parkingType,
            UUID rateId) {
    }

    public record DecideRequest(
            @Schema(description = "Slot to allocate on approval (otherwise auto-selected when needed)") UUID slotId,
            @Size(max = 300) String remarks) {
    }

    public record VisitorBlock(UUID visitorId, String name, String mobile, String purpose, Instant expectedArrival,
                               Instant expectedDeparture) {
    }

    public record RequestResponse(
            UUID id, String referenceNumber, RequestType type, RequestStatus status, FlatRef flat, VehicleRef vehicle,
            String vehicleNumber, VehicleType vehicleType, AreaRef preferredArea, RateResponse rate, Instant validFrom,
            Instant validUntil, String reason, Instant createdAt, Instant decidedAt, String decidedBy,
            String decisionRemarks, SlotRef assignedSlot, PermitRef permit, VisitorBlock visitor, QuoteResponse quote,
            String requestedBy, boolean autoApproved) {
    }

    // -------------------------------------------------------------- sessions

    public record Charges(
            RateResponse rate,
            Money estimated,
            @JsonProperty("final") Money finalAmount,
            ChargeStatus paymentStatus,
            UUID paymentId,
            Instant paidUntil,
            Money paid,
            Money outstanding) {
    }

    public record SessionResponse(
            UUID id, String vehicleNumber, VehicleType vehicleType, Category category, VehicleRef vehicle,
            UUID visitorId, String visitorName, FlatRef flat, SlotRef slot, PermitRef permit, GateRef entryGate,
            Instant entryAt, GateRef exitGate, Instant exitAt, Instant validUntil, SessionDisplayStatus status,
            Charges charges, boolean exitAllowed, String exitBlockedReason, String recordedBy,
            boolean emergencyOverride, String overrideReason, UUID parkingRequestId, List<TimelineEvent> history) {
    }

    public record VerifyRequest(
            @NotBlank(message = "Enter the vehicle number") String vehicleNumber,
            @Schema(description = "ENTRY (default)") String purpose,
            @Schema(description = "Helps when the vehicle is not registered") VehicleType vehicleType) {
    }

    public record VerificationVisitor(UUID visitorId, String name, DisplayStatus status, FlatRef flat) {
    }

    public record VerificationResponse(
            UUID verificationId, String vehicleNumber, VehicleType vehicleType, VerificationDecision decision,
            Category category, String summary, VehicleRef vehicle, FlatRef flat, PermitRef permit, SlotRef slot,
            Instant validUntil, VerificationVisitor visitor, UUID parkingRequestId, SessionResponse activeSession,
            boolean paidParkingOffered, List<RateResponse> rates, List<String> reasons, List<String> warnings,
            boolean emergencyOverrideAllowed, Instant expiresAt) {
    }

    @Schema(description = "Records entry for a fresh verification. entryType = the verified category, PAID when paid "
            + "parking was offered (rateId required) or EMERGENCY with overrideReason when the society allows it")
    public record EntryRequest(
            @NotNull(message = "verificationId is required") UUID verificationId,
            @NotBlank String vehicleNumber,
            @NotNull(message = "entryType is required") Category entryType,
            UUID visitorId,
            UUID parkingRequestId,
            UUID rateId,
            UUID slotId,
            VehicleType vehicleType,
            @Size(max = 200) String overrideReason) {
    }

    public record CancelSessionRequest(@NotBlank(message = "A reason is required") @Size(max = 200) String reason) {
    }

    public record VehicleSearchResult(
            String vehicleNumber, VehicleType vehicleType, Category category, String make, String model, String color,
            String ownerLabel, FlatRef flat, SlotRef slot, PermitRef permit, RegistrationStatus registrationStatus,
            boolean insideNow, UUID activeSessionId, UUID vehicleId) {
    }

    // ------------------------------------------------------- summary/dashboard

    public record Alert(String id, AlertType type, AlertSeverity severity, String title, String message,
                        UUID referenceId, Instant at) {
    }

    public record ParkingSummary(
            long vehicleCount, long approvedVehicleCount, long pendingVehicleCount, long activeAllocationCount,
            long pendingRequestCount, long activePermitCount, long visitorVehiclesInside, Money unpaidAmount,
            List<Alert> alerts) {
    }

    public record GuardParkingDashboard(
            long vehiclesInside, long todayEntries, long todayExits, long visitorVehiclesWaiting, long pendingRequests,
            long activePaidSessions, long overstayCount, List<AreaResponse> areas, List<SessionResponse> recentSessions) {
    }
}
