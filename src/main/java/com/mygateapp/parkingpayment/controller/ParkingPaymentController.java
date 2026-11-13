package com.mygateapp.parkingpayment.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.parkingpayment.dto.PaymentDtos;
import com.mygateapp.parkingpayment.enums.PaymentEnums.BillingUnit;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentPurpose;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentStatus;
import com.mygateapp.parkingpayment.enums.PaymentEnums.RateAppliesTo;
import com.mygateapp.parkingpayment.service.ParkingChargeService;
import com.mygateapp.parkingpayment.service.ParkingPaymentService;
import com.mygateapp.parkingpayment.service.RatePlanService;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Parking pricing & payments", description = "Rate plans, server-side quotes, payments, refunds, waivers, receipts")
public class ParkingPaymentController {

    private static final String ADMINS = "hasAnyRole('SOCIETY_ADMIN','SUPER_ADMIN')";
    private static final String MEMBERS = "hasAnyRole('RESIDENT','GUARD','SOCIETY_ADMIN','SUPER_ADMIN')";

    private final RatePlanService rates;
    private final ParkingChargeService charges;
    private final ParkingPaymentService payments;

    public ParkingPaymentController(RatePlanService rates, ParkingChargeService charges, ParkingPaymentService payments) {
        this.rates = rates;
        this.charges = charges;
        this.payments = payments;
    }

    // ------------------------------------------------------------------ rates

    @GetMapping("/api/v1/parking/rates")
    @PreAuthorize(MEMBERS)
    @Operation(summary = "Rate cards in effect (admins may add includeInactive=true)")
    public List<PaymentDtos.RateResponse> rates(@RequestParam(required = false) VehicleType vehicleType,
                                                @RequestParam(required = false) BillingUnit billingUnit,
                                                @RequestParam(required = false) RateAppliesTo appliesTo,
                                                @RequestParam(defaultValue = "false") boolean includeInactive) {
        return rates.list(vehicleType, billingUnit, appliesTo, includeInactive);
    }

    @GetMapping("/api/v1/parking/rates/{id}")
    @PreAuthorize(MEMBERS)
    public PaymentDtos.RateResponse rate(@PathVariable UUID id) {
        return rates.get(id);
    }

    @PostMapping("/api/v1/parking/rates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    public PaymentDtos.RateResponse createRate(@Valid @RequestBody PaymentDtos.CreateRateRequest request) {
        return rates.create(request);
    }

    @PatchMapping("/api/v1/parking/rates/{id}")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Change a rate. Running sessions keep the pricing they started with.")
    public PaymentDtos.RateResponse updateRate(@PathVariable UUID id, @Valid @RequestBody PaymentDtos.UpdateRateRequest request) {
        return rates.update(id, request);
    }

    @PostMapping("/api/v1/parking/quotes")
    @PreAuthorize(MEMBERS)
    @Operation(summary = "Server-computed charge: an estimate {rateId, vehicleType, from, until} or the amount due to "
            + "close a session now {sessionId}. Valid for 10 minutes.")
    public PaymentDtos.QuoteResponse quote(@Valid @RequestBody PaymentDtos.QuoteRequest request) {
        return charges.quote(request);
    }

    // --------------------------------------------------------------- payments

    @PostMapping("/api/v1/parking/sessions/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Guard records CASH / UPI_AT_GATE / CARD_AT_GATE for a fresh session quote (send "
            + "Idempotency-Key). 409 QUOTE_EXPIRED when the charge changed.")
    public PaymentDtos.PaymentResponse gatePayment(@PathVariable UUID id, @Valid @RequestBody PaymentDtos.GatePaymentRequest request) {
        return payments.recordGatePayment(id, request);
    }

    @PostMapping("/api/v1/parking/payments/manual")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    @Operation(summary = "Society office records a cash / UPI / bank transfer / card payment for a session or permit. "
            + "The amount is the server's outstanding amount; confirm=false keeps it PENDING.")
    public PaymentDtos.PaymentResponse manual(@Valid @RequestBody PaymentDtos.ManualPaymentRequest request) {
        return payments.recordManual(request);
    }

    @PostMapping("/api/v1/parking/payments/{id}/confirm")
    @PreAuthorize(ADMINS)
    public PaymentDtos.PaymentResponse confirm(@PathVariable UUID id,
                                               @Valid @RequestBody(required = false) PaymentDtos.ConfirmPaymentRequest request) {
        return payments.confirm(id, request);
    }

    @PostMapping("/api/v1/parking/payments/{id}/fail")
    @PreAuthorize(ADMINS)
    public PaymentDtos.PaymentResponse fail(@PathVariable UUID id, @Valid @RequestBody PaymentDtos.ReasonRequest request) {
        return payments.fail(id, request.reason());
    }

    @PostMapping("/api/v1/parking/payments/{id}/refund")
    @PreAuthorize(ADMINS)
    public PaymentDtos.PaymentResponse refund(@PathVariable UUID id, @Valid @RequestBody PaymentDtos.ReasonRequest request) {
        return payments.refund(id, request.reason());
    }

    @PostMapping("/api/v1/parking/payments/waivers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    @Operation(summary = "Waive the outstanding charge of a session or permit (adjustment with reason; audited)")
    public PaymentDtos.PaymentResponse waive(@Valid @RequestBody PaymentDtos.WaiverRequest request) {
        return payments.waive(request);
    }

    @GetMapping("/api/v1/parking/payments")
    @PreAuthorize("hasAnyRole('RESIDENT','SOCIETY_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Residents: payments of their flats. Admins: all payments of the society.")
    public PageResponse<PaymentDtos.PaymentResponse> list(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) PaymentPurpose purpose,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) UUID permitId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return payments.list(status, purpose, sessionId, permitId, from, to, page, size);
    }

    @GetMapping("/api/v1/parking/payments/{id}")
    @PreAuthorize(MEMBERS)
    @Operation(summary = "Payment with receipt and history")
    public PaymentDtos.PaymentResponse payment(@PathVariable UUID id) {
        return payments.get(id);
    }

    @GetMapping("/api/v1/parking/receipts/{id}")
    @PreAuthorize(MEMBERS)
    @Operation(summary = "Receipt by receipt id (or payment id)")
    public PaymentDtos.ReceiptResponse receipt(@PathVariable UUID id) {
        return payments.receipt(id);
    }
}
