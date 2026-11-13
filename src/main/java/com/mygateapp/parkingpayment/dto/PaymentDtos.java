package com.mygateapp.parkingpayment.dto;

import com.mygateapp.parking.dto.ParkingRefs.AreaRef;
import com.mygateapp.parking.dto.ParkingRefs.Money;
import com.mygateapp.parkingpayment.enums.PaymentEnums.BillingUnit;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentEventType;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentMethod;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentPurpose;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentStatus;
import com.mygateapp.parkingpayment.enums.PaymentEnums.RateAppliesTo;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Rates, quotes, payments and receipts. Amounts in requests are validated but never trusted as totals. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    // ------------------------------------------------------------------ rates

    public record CreateRateRequest(
            @Schema(example = "VIS-CAR-HR") @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{2,30}$", message = "Code: 2-30 letters, digits or hyphens") String code,
            @Schema(example = "Visitor car · hourly") @NotBlank @Size(max = 80) String name,
            @Size(max = 300) String description,
            @Schema(description = "Optional: rate applies only in this area") UUID areaId,
            @Schema(description = "Null = all vehicle types") VehicleType vehicleType,
            @NotNull RateAppliesTo appliesTo,
            @NotNull BillingUnit billingUnit,
            @Schema(description = "Base rate per billing unit", example = "30.00")
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Min(0) @Max(10080) Integer freeMinutes,
            @Min(0) @Max(240) Integer gracePeriodMinutes,
            @Schema(description = "Daily cap for hourly plans") @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal maxPerDay,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal overstayHourlyRate,
            @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal taxPercent,
            @Size(max = 30) String taxLabel,
            Instant effectiveFrom,
            Instant effectiveUntil) {
    }

    /** All fields optional. Changes apply to new sessions and quotes only (existing sessions keep their snapshot). */
    public record UpdateRateRequest(
            @Size(min = 1, max = 80) String name,
            @Size(max = 300) String description,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Min(0) @Max(10080) Integer freeMinutes,
            @Min(0) @Max(240) Integer gracePeriodMinutes,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal maxPerDay,
            Boolean clearMaxPerDay,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal overstayHourlyRate,
            @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal taxPercent,
            @Size(max = 30) String taxLabel,
            Instant effectiveUntil,
            Boolean active) {
    }

    public record RateResponse(
            UUID id, String code, String name, String description, BillingUnit billingUnit, VehicleType vehicleType,
            RateAppliesTo appliesTo, Money amount, int freeMinutes, int gracePeriodMinutes, Money maxPerDay,
            Money overstayHourlyRate, String taxPercent, String taxLabel, AreaRef area, Instant effectiveFrom,
            Instant effectiveUntil, boolean active) {
    }

    // ----------------------------------------------------------------- quotes

    @Schema(description = "Either {rateId, vehicleType, from, until} for an estimate, or {sessionId} for the charge to "
            + "close an active session now")
    public record QuoteRequest(UUID rateId, VehicleType vehicleType, Instant from, Instant until, UUID sessionId) {
    }

    public record QuoteLine(String label, Money amount) {
    }

    /** {@code total} = amount due now (subtotal + tax − alreadyPaid). */
    public record QuoteResponse(
            UUID id, RateResponse rate, UUID sessionId, UUID permitId, Instant from, Instant until, int billableMinutes,
            List<QuoteLine> lines, Money subtotal, Money tax, String taxLabel, Money alreadyPaid, Money total,
            Instant calculatedAt, Instant expiresAt) {
    }

    // --------------------------------------------------------------- payments

    /** Guard records a payment at the gate for a server quote of the session. */
    public record GatePaymentRequest(
            @NotNull(message = "quoteId is required") UUID quoteId,
            @NotNull(message = "method is required") PaymentMethod method,
            @Size(max = 100) String reference) {
    }

    /**
     * Society office records a manual payment for a session or permit. {@code amount}, when sent, must equal the
     * server-computed outstanding amount. {@code confirm=false} records it as PENDING (e.g. a bank transfer awaiting
     * reconciliation).
     */
    public record ManualPaymentRequest(
            UUID sessionId,
            UUID permitId,
            @NotNull(message = "method is required") PaymentMethod method,
            @Size(max = 100) String reference,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Size(max = 80) String payerName,
            @Size(max = 200) String notes,
            Boolean confirm) {
    }

    public record ConfirmPaymentRequest(PaymentMethod method, @Size(max = 100) String reference) {
    }

    public record ReasonRequest(@NotBlank(message = "A reason is required") @Size(max = 200) String reason) {
    }

    /** Waives the outstanding charge of a session or permit (an adjustment, recorded with reason and actor). */
    public record WaiverRequest(UUID sessionId, UUID permitId,
                                @NotBlank(message = "A reason is required") @Size(max = 200) String reason) {
    }

    public record PaymentEventView(PaymentEventType type, PaymentStatus status, Money amount, PaymentMethod method,
                                   String reference, String reason, String actor, Instant at) {
    }

    public record ReceiptResponse(
            UUID id, UUID paymentId, String receiptNumber, Instant issuedAt, String payerName, String societyName,
            String vehicleNumber, List<QuoteLine> lines, Money subtotal, Money tax, String taxLabel, Money total,
            String issuedBy) {
    }

    public record PaymentResponse(
            UUID id, PaymentStatus status, Money amount, PaymentMethod method, PaymentPurpose purpose, UUID sessionId,
            UUID permitId, String vehicleNumber, String description, String reference, Instant createdAt,
            Instant paidAt, String recordedBy, String confirmedBy, String statusReason, ReceiptResponse receipt,
            List<PaymentEventView> history) {
    }
}
