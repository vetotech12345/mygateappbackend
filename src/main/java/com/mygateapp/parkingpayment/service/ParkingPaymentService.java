package com.mygateapp.parkingpayment.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.Ids;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.TimeRanges;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.parking.dto.ParkingRefs.Money;
import com.mygateapp.parking.entity.ParkingAllocation;
import com.mygateapp.parking.entity.ParkingMovement;
import com.mygateapp.parking.entity.ParkingPermit;
import com.mygateapp.parking.entity.ParkingSession;
import com.mygateapp.parking.entity.SocietyParkingSettings;
import com.mygateapp.parking.enums.ParkingEnums.AllocationStatus;
import com.mygateapp.parking.enums.ParkingEnums.ChargeStatus;
import com.mygateapp.parking.enums.ParkingEnums.MovementType;
import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.enums.ParkingEnums.SessionStatus;
import com.mygateapp.parking.repository.ParkingAllocationRepository;
import com.mygateapp.parking.repository.ParkingMovementRepository;
import com.mygateapp.parking.repository.ParkingPermitRepository;
import com.mygateapp.parking.repository.ParkingSessionRepository;
import com.mygateapp.parking.service.ParkingAccess;
import com.mygateapp.parking.service.ParkingAllocationService;
import com.mygateapp.parking.service.ParkingRefLoader;
import com.mygateapp.parking.service.ParkingSettingsStore;
import com.mygateapp.parking.service.ParkingSupport;
import com.mygateapp.parkingpayment.dto.PaymentDtos.ConfirmPaymentRequest;
import com.mygateapp.parkingpayment.dto.PaymentDtos.GatePaymentRequest;
import com.mygateapp.parkingpayment.dto.PaymentDtos.ManualPaymentRequest;
import com.mygateapp.parkingpayment.dto.PaymentDtos.PaymentEventView;
import com.mygateapp.parkingpayment.dto.PaymentDtos.PaymentResponse;
import com.mygateapp.parkingpayment.dto.PaymentDtos.QuoteLine;
import com.mygateapp.parkingpayment.dto.PaymentDtos.ReceiptResponse;
import com.mygateapp.parkingpayment.dto.PaymentDtos.WaiverRequest;
import com.mygateapp.parkingpayment.entity.ParkingPayment;
import com.mygateapp.parkingpayment.entity.ParkingPaymentEvent;
import com.mygateapp.parkingpayment.entity.ParkingQuote;
import com.mygateapp.parkingpayment.entity.ParkingReceipt;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentEventType;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentMethod;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentPurpose;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentStatus;
import com.mygateapp.parkingpayment.repository.PaymentEventRepository;
import com.mygateapp.parkingpayment.repository.PaymentRepository;
import com.mygateapp.parkingpayment.repository.QuoteRepository;
import com.mygateapp.parkingpayment.repository.ReceiptRepository;
import com.mygateapp.security.Permission;
import com.mygateapp.security.UserAccess;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment records for sessions and permits. Amounts always come from server computations (quotes, final charges,
 * permit fees). Manual confirmation requires an authorised role and stores who recorded and who confirmed it.
 * Online payment is not available (see {@link OnlinePaymentGateway}).
 */
@Service
public class ParkingPaymentService {

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final ReceiptRepository receipts;
    private final QuoteRepository quotes;
    private final ParkingSessionRepository sessions;
    private final ParkingPermitRepository permits;
    private final ParkingAllocationRepository allocations;
    private final ParkingMovementRepository movements;
    private final ParkingChargeService charges;
    private final ParkingAllocationService allocationService;
    private final ParkingSettingsStore settingsStore;
    private final ParkingAccess access;
    private final ParkingSupport support;
    private final ParkingRefLoader refLoader;
    private final AuditService audit;
    private final Clock clock;

    public ParkingPaymentService(PaymentRepository payments, PaymentEventRepository events, ReceiptRepository receipts,
                                 QuoteRepository quotes, ParkingSessionRepository sessions, ParkingPermitRepository permits,
                                 ParkingAllocationRepository allocations, ParkingMovementRepository movements,
                                 ParkingChargeService charges, ParkingAllocationService allocationService,
                                 ParkingSettingsStore settingsStore, ParkingAccess access, ParkingSupport support,
                                 ParkingRefLoader refLoader, AuditService audit, Clock clock) {
        this.refLoader = refLoader;
        this.payments = payments;
        this.events = events;
        this.receipts = receipts;
        this.quotes = quotes;
        this.sessions = sessions;
        this.permits = permits;
        this.allocations = allocations;
        this.movements = movements;
        this.charges = charges;
        this.allocationService = allocationService;
        this.settingsStore = settingsStore;
        this.access = access;
        this.support = support;
        this.audit = audit;
        this.clock = clock;
    }

    // ============================================================ gate payment

    /** Guard records cash / UPI / card at the gate for a fresh quote of the session. */
    @Transactional
    public PaymentResponse recordGatePayment(UUID sessionId, GatePaymentRequest request) {
        UserAccess.GuardAccess guard = access.requireGateGuard();
        ParkingSession session = lockSession(sessionId, guard.societyId());
        if (!session.isActive()) {
            throw new ApiException(ErrorCode.SESSION_ALREADY_CLOSED);
        }
        SocietyParkingSettings settings = settingsStore.settingsFor(session.getSocietyId());
        if (!PaymentMethod.GATE.contains(request.method())
                || !ParkingSettingsStore.acceptedMethods(settings).contains(request.method())) {
            throw ApiException.field(ErrorCode.PAYMENT_METHOD_NOT_ACCEPTED, "method",
                    ErrorCode.PAYMENT_METHOD_NOT_ACCEPTED.defaultMessage());
        }
        ParkingQuote quote = charges.requireQuote(request.quoteId(), session.getSocietyId());
        Instant now = clock.instant();
        if (!session.getId().equals(quote.getSessionId())) {
            throw new ApiException(ErrorCode.QUOTE_NOT_FOUND, "This charge belongs to another session.");
        }
        ParkingChargeService.SessionCharge current = charges.charge(session, now);
        if (current == null || current.outstanding().signum() == 0) {
            throw new ApiException(ErrorCode.NOTHING_TO_PAY);
        }
        if (!now.isBefore(quote.getExpiresAt()) || quote.getTotal().compareTo(current.outstanding()) != 0) {
            throw new ApiException(ErrorCode.QUOTE_EXPIRED);
        }
        String actorName = support.displayName(access.currentUserId());
        ParkingPayment payment = newPayment(session.getSocietyId(), PaymentPurpose.SESSION, session.getId(), null,
                quote.getId(), session.getFlatId(), payerName(session), session.getVehicleNumber(), quote.getTotal(),
                quote.getCurrency(), "Parking charges for " + session.getVehicleNumber());
        payment.setMethod(request.method());
        payment.setReference(Strings.trimToNull(request.reference()));
        markPaid(payment, now, actorName);
        save(payment);
        event(payment, PaymentEventType.CREATED, null, actorName);
        event(payment, PaymentEventType.CONFIRMED, null, actorName);
        ParkingReceipt receipt = issueReceipt(payment, quote, actorName);

        Duration paidWindow = Duration.ofMinutes(Math.max(current.rate().gracePeriodMinutes(), 15));
        session.setPaidUntil(quote.getPeriodUntil().plus(paidWindow));
        applySettlement(payment, payment.getAmount());
        movement(session, MovementType.PAYMENT, guard.gateId(), actorName,
                "Payment recorded: " + request.method() + ", receipt " + receipt.getReceiptNumber());
        audit.record(session.getSocietyId(), "PARKING_PAYMENT_CONFIRMED", "PARKING_PAYMENT", payment.getId(),
                "Gate payment for " + session.getVehicleNumber(), Map.of("amount", payment.getAmount().toPlainString(),
                        "method", request.method().name(), "receipt", receipt.getReceiptNumber()));
        support.notifyFlat(session.getFlatId(), session.getSocietyId(), "Parking payment received",
                "Payment received for " + session.getVehicleNumber() + ". Receipt " + receipt.getReceiptNumber() + ".",
                Notification.ReferenceType.PARKING_PAYMENT, payment.getId());
        return toResponse(payment, true);
    }

    // ========================================================== office payments

    @Transactional
    public PaymentResponse recordManual(ManualPaymentRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_PAYMENT_MANAGE);
        Target target = target(request.sessionId(), request.permitId(), societyId);
        SocietyParkingSettings settings = settingsStore.settingsFor(societyId);
        if (!PaymentMethod.MANUAL.contains(request.method())
                || !ParkingSettingsStore.acceptedMethods(settings).contains(request.method())) {
            throw ApiException.field(ErrorCode.PAYMENT_METHOD_NOT_ACCEPTED, "method",
                    ErrorCode.PAYMENT_METHOD_NOT_ACCEPTED.defaultMessage());
        }
        Optional<ParkingPayment> pending = pendingFor(target);
        if (pending.isPresent()) {
            throw new ApiException(ErrorCode.PAYMENT_INVALID_STATE,
                    "A pending payment already exists for this item. Confirm payment " + pending.get().getId() + " instead.");
        }
        BigDecimal outstanding = outstanding(target);
        if (outstanding.signum() == 0) {
            throw new ApiException(ErrorCode.NOTHING_TO_PAY);
        }
        if (request.amount() != null && PricingCalculator.money(request.amount()).compareTo(outstanding) != 0) {
            throw ApiException.field(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "amount",
                    "The outstanding amount is " + outstanding.toPlainString() + ".");
        }
        String actorName = support.displayName(access.currentUserId());
        ParkingPayment payment = newPayment(societyId, target.purpose(), target.sessionId(), target.permitId(), null,
                target.flatId(), Strings.trimToNull(request.payerName()) != null ? request.payerName().trim() : target.payerName(),
                target.vehicleNumber(), outstanding, target.currency(),
                Strings.trimToNull(request.notes()) != null ? request.notes().trim() : target.description());
        payment.setMethod(request.method());
        payment.setReference(Strings.trimToNull(request.reference()));
        boolean confirm = !Boolean.FALSE.equals(request.confirm());
        if (confirm) {
            markPaid(payment, clock.instant(), actorName);
        }
        save(payment);
        event(payment, PaymentEventType.CREATED, null, actorName);
        if (confirm) {
            event(payment, PaymentEventType.CONFIRMED, null, actorName);
            issueReceipt(payment, null, actorName);
            applySettlement(payment, payment.getAmount());
        } else if (target.session() != null) {
            refreshSessionStatus(target.session());
        }
        audit.record(societyId, confirm ? "PARKING_PAYMENT_CONFIRMED" : "PARKING_PAYMENT_RECORDED", "PARKING_PAYMENT",
                payment.getId(), "Manual payment for " + target.vehicleNumber(),
                Map.of("amount", payment.getAmount().toPlainString(), "method", request.method().name(),
                        "status", payment.getStatus().name()));
        if (confirm) {
            notifyPaid(payment);
        }
        return toResponse(payment, true);
    }

    @Transactional
    public PaymentResponse confirm(UUID id, ConfirmPaymentRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_PAYMENT_MANAGE);
        ParkingPayment payment = lock(id, societyId);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new ApiException(ErrorCode.PAYMENT_INVALID_STATE);
        }
        PaymentMethod method = request != null && request.method() != null ? request.method() : payment.getMethod();
        if (method == null || !PaymentMethod.MANUAL.contains(method)) {
            throw ApiException.field(ErrorCode.PAYMENT_METHOD_NOT_ACCEPTED, "method", "Choose how the payment was received.");
        }
        payment.setMethod(method);
        if (request != null && request.reference() != null) {
            payment.setReference(Strings.trimToNull(request.reference()));
        }
        String actorName = support.displayName(access.currentUserId());
        markPaid(payment, clock.instant(), actorName);
        save(payment);
        event(payment, PaymentEventType.CONFIRMED, null, actorName);
        ParkingQuote quote = payment.getQuoteId() == null ? null : quotes.findById(payment.getQuoteId()).orElse(null);
        issueReceipt(payment, quote, actorName);
        applySettlement(payment, payment.getAmount());
        audit.record(societyId, "PARKING_PAYMENT_CONFIRMED", "PARKING_PAYMENT", id, "Payment confirmed",
                Map.of("amount", payment.getAmount().toPlainString(), "method", method.name()));
        notifyPaid(payment);
        return toResponse(payment, true);
    }

    @Transactional
    public PaymentResponse fail(UUID id, String reason) {
        UUID societyId = access.requireAdmin(Permission.PARKING_PAYMENT_MANAGE);
        ParkingPayment payment = lock(id, societyId);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new ApiException(ErrorCode.PAYMENT_INVALID_STATE);
        }
        payment.setStatus(PaymentStatus.FAILED);
        payment.setStatusReason(reason.trim());
        save(payment);
        event(payment, PaymentEventType.FAILED, reason, support.displayName(access.currentUserId()));
        sessionOf(payment).ifPresent(this::refreshSessionStatus);
        audit.record(societyId, "PARKING_PAYMENT_FAILED", "PARKING_PAYMENT", id, "Payment marked failed",
                Map.of("reason", reason));
        return toResponse(payment, true);
    }

    @Transactional
    public PaymentResponse refund(UUID id, String reason) {
        UUID societyId = access.requireAdmin(Permission.PARKING_PAYMENT_MANAGE);
        ParkingPayment payment = lock(id, societyId);
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new ApiException(ErrorCode.PAYMENT_INVALID_STATE, "Only paid payments can be refunded.");
        }
        Instant now = clock.instant();
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(now);
        payment.setRefundedBy(access.currentUserId());
        payment.setStatusReason(reason.trim());
        save(payment);
        event(payment, PaymentEventType.REFUNDED, reason, support.displayName(access.currentUserId()));
        applySettlement(payment, payment.getAmount().negate());
        audit.record(societyId, "PARKING_PAYMENT_REFUNDED", "PARKING_PAYMENT", id, "Payment refunded",
                Map.of("amount", payment.getAmount().toPlainString(), "reason", reason));
        support.notifyFlat(payment.getFlatId(), societyId, "Parking payment refunded",
                "A parking payment for " + payment.getVehicleNumber() + " was refunded.",
                Notification.ReferenceType.PARKING_PAYMENT, id);
        return toResponse(payment, true);
    }

    /** Waives what is outstanding on a session or permit (an adjustment with reason and actor). */
    @Transactional
    public PaymentResponse waive(WaiverRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_PAYMENT_MANAGE);
        Target target = target(request.sessionId(), request.permitId(), societyId);
        String actorName = support.displayName(access.currentUserId());
        Instant now = clock.instant();
        ParkingPayment payment = pendingFor(target).map(p -> lock(p.getId(), societyId)).orElse(null);
        if (payment == null) {
            BigDecimal outstanding = outstanding(target);
            if (outstanding.signum() == 0) {
                throw new ApiException(ErrorCode.NOTHING_TO_PAY);
            }
            payment = newPayment(societyId, target.purpose(), target.sessionId(), target.permitId(), null,
                    target.flatId(), target.payerName(), target.vehicleNumber(), outstanding, target.currency(),
                    target.description());
            save(payment);
            event(payment, PaymentEventType.CREATED, null, actorName);
        }
        payment.setMethod(PaymentMethod.WAIVER);
        payment.setStatus(PaymentStatus.WAIVED);
        payment.setPaidAt(now);
        payment.setConfirmedBy(access.currentUserId());
        payment.setConfirmedByName(actorName);
        payment.setStatusReason(request.reason().trim());
        save(payment);
        event(payment, PaymentEventType.WAIVED, request.reason(), actorName);
        applySettlement(payment, payment.getAmount());
        audit.record(societyId, "PARKING_PAYMENT_WAIVED", "PARKING_PAYMENT", payment.getId(),
                "Charges waived for " + target.vehicleNumber(),
                Map.of("amount", payment.getAmount().toPlainString(), "reason", request.reason()));
        return toResponse(payment, true);
    }

    // ================================================================== reads

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> list(PaymentStatus status, PaymentPurpose purpose, UUID sessionId, UUID permitId,
                                              Instant from, Instant to, Integer page, Integer size) {
        ParkingAccess.Scope scope = access.readScope(Permission.PARKING_LOG_VIEW, false);
        Page<ParkingPayment> result = payments.search(scope.societyId(), scope.resident(), Ids.orNil(scope.flatIds()),
                status, purpose, sessionId, permitId, TimeRanges.orMin(from), TimeRanges.orMax(to),
                Pages.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, ParkingReceipt> receiptByPayment = result.getContent().isEmpty() ? Map.of()
                : receipts.findByPaymentIdIn(result.getContent().stream().map(ParkingPayment::getId).toList()).stream()
                        .collect(Collectors.toMap(ParkingReceipt::getPaymentId, Function.identity()));
        return PageResponse.of(result, p -> toResponse(p, receiptByPayment.get(p.getId()), List.of()));
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID id) {
        ParkingAccess.Scope scope = access.readScope(Permission.PARKING_LOG_VIEW, true);
        ParkingPayment payment = payments.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND));
        requireVisible(payment, scope);
        return toResponse(payment, true);
    }

    @Transactional(readOnly = true)
    public ReceiptResponse receipt(UUID id) {
        ParkingAccess.Scope scope = access.readScope(Permission.PARKING_LOG_VIEW, true);
        ParkingReceipt receipt = receipts.findById(id)
                .or(() -> receipts.findByPaymentId(id))
                .orElseThrow(() -> new ApiException(ErrorCode.RECEIPT_NOT_FOUND));
        ParkingPayment payment = payments.findById(receipt.getPaymentId())
                .orElseThrow(() -> new ApiException(ErrorCode.RECEIPT_NOT_FOUND));
        requireVisible(payment, scope);
        return toReceipt(receipt);
    }

    // ============================================================== internal

    /** Called at exit when charges remain and the society lets the vehicle leave unpaid. */
    public ParkingPayment createPendingForSession(ParkingSession session, BigDecimal amount, String description) {
        ParkingPayment payment = newPayment(session.getSocietyId(), PaymentPurpose.SESSION, session.getId(), null, null,
                session.getFlatId(), payerName(session), session.getVehicleNumber(), amount, session.getCurrency(),
                description);
        save(payment);
        event(payment, PaymentEventType.CREATED, description, support.displayName(access.currentUserId()));
        return payment;
    }

    /** Called when a paid permit request is approved: the permit waits for this payment. */
    public ParkingPayment createPendingForPermit(ParkingPermit permit, ParkingQuote quote, String payerName) {
        ParkingPayment payment = newPayment(permit.getSocietyId(), PaymentPurpose.PERMIT, null, permit.getId(), quote.getId(),
                permit.getFlatId(), payerName, permit.getVehicleNumber(), quote.getTotal(), quote.getCurrency(),
                "Parking permit " + permit.getPermitNumber());
        save(payment);
        event(payment, PaymentEventType.CREATED, null, support.displayName(access.currentUserId()));
        return payment;
    }

    /** Recomputes a session's payment status from its settled amount and current (or final) charge. */
    public void refreshSessionStatus(ParkingSession session) {
        if (!session.isPaid()) {
            session.setPaymentStatus(ChargeStatus.NOT_APPLICABLE);
            return;
        }
        if (session.getStatus() == SessionStatus.CANCELLED) {
            return;
        }
        ParkingChargeService.SessionCharge charge = charges.charge(session, clock.instant());
        BigDecimal due = session.getStatus() == SessionStatus.COMPLETED && session.getFinalAmount() != null
                ? session.getFinalAmount() : charge.charge().total();
        boolean waived = payments.existsBySessionIdAndStatus(session.getId(), PaymentStatus.WAIVED);
        boolean failed = payments.existsBySessionIdAndStatus(session.getId(), PaymentStatus.FAILED);
        BigDecimal settled = session.getPaidAmount();
        ChargeStatus status;
        if (session.getStatus() == SessionStatus.COMPLETED && due.signum() == 0) {
            status = ChargeStatus.NOT_APPLICABLE;
        } else if (settled.signum() > 0 && settled.compareTo(due) >= 0) {
            status = waived ? ChargeStatus.WAIVED : ChargeStatus.PAID;
        } else {
            status = failed && !payments.existsBySessionIdAndStatus(session.getId(), PaymentStatus.PENDING)
                    && session.getStatus() == SessionStatus.COMPLETED ? ChargeStatus.FAILED : ChargeStatus.PENDING;
        }
        session.setPaymentStatus(status);
    }

    public Optional<ParkingPayment> latestForSession(UUID sessionId) {
        return payments.findBySessionIdOrderByCreatedAtDesc(sessionId).stream().findFirst();
    }

    // ================================================================ helpers

    private record Target(PaymentPurpose purpose, ParkingSession session, ParkingPermit permit, UUID flatId,
                          String vehicleNumber, String currency, String payerName, String description) {

        UUID sessionId() {
            return session == null ? null : session.getId();
        }

        UUID permitId() {
            return permit == null ? null : permit.getId();
        }
    }

    private Target target(UUID sessionId, UUID permitId, UUID societyId) {
        if ((sessionId == null) == (permitId == null)) {
            throw ApiException.field("sessionId", "Give either sessionId or permitId.");
        }
        if (sessionId != null) {
            ParkingSession session = lockSession(sessionId, societyId);
            if (!session.isPaid()) {
                throw new ApiException(ErrorCode.NOTHING_TO_PAY, "This is not a paid parking session.");
            }
            return new Target(PaymentPurpose.SESSION, session, null, session.getFlatId(), session.getVehicleNumber(),
                    session.getCurrency(), payerName(session), "Parking charges for " + session.getVehicleNumber());
        }
        ParkingPermit permit = permits.findByIdForUpdate(permitId).orElseThrow(() -> new ApiException(ErrorCode.PERMIT_NOT_FOUND));
        access.ensureSameSociety(permit.getSocietyId(), societyId, ErrorCode.PERMIT_NOT_FOUND);
        if (permit.getStatus() != PermitStatus.PENDING_PAYMENT) {
            throw new ApiException(ErrorCode.NOTHING_TO_PAY, "This permit has no fee outstanding.");
        }
        String currency = quotes.findFirstByPermitIdOrderByCalculatedAtDesc(permit.getId())
                .map(ParkingQuote::getCurrency).orElse(settingsStore.settingsFor(societyId).getCurrency());
        return new Target(PaymentPurpose.PERMIT, null, permit, permit.getFlatId(), permit.getVehicleNumber(), currency,
                permit.getHolderName(), "Parking permit " + permit.getPermitNumber());
    }

    private BigDecimal outstanding(Target target) {
        if (target.session() != null) {
            ParkingChargeService.SessionCharge charge = charges.charge(target.session(), clock.instant());
            return charge == null ? BigDecimal.ZERO.setScale(2) : charge.outstanding();
        }
        return quotes.findFirstByPermitIdOrderByCalculatedAtDesc(target.permitId())
                .map(ParkingQuote::getTotal)
                .orElseThrow(() -> new ApiException(ErrorCode.NOTHING_TO_PAY, "No fee has been calculated for this permit."));
    }

    private Optional<ParkingPayment> pendingFor(Target target) {
        List<ParkingPayment> pending = target.session() != null
                ? payments.findBySessionIdAndStatus(target.sessionId(), PaymentStatus.PENDING)
                : payments.findByPermitIdAndStatus(target.permitId(), PaymentStatus.PENDING);
        return pending.stream().findFirst();
    }

    /** Adds (or, for refunds, subtracts) a settled amount to the target and updates its state. */
    private void applySettlement(ParkingPayment payment, BigDecimal delta) {
        if (payment.getPurpose() == PaymentPurpose.SESSION) {
            ParkingSession session = lockSession(payment.getSessionId(), payment.getSocietyId());
            session.setPaidAmount(PricingCalculator.money(session.getPaidAmount().add(delta).max(BigDecimal.ZERO)));
            refreshSessionStatus(session);
            return;
        }
        ParkingPermit permit = permits.findByIdForUpdate(payment.getPermitId())
                .orElseThrow(() -> new ApiException(ErrorCode.PERMIT_NOT_FOUND));
        if (delta.signum() > 0 && permit.getStatus() == PermitStatus.PENDING_PAYMENT) {
            permit.setStatus(PermitStatus.ACTIVE);
            if (permit.getAllocationId() != null) {
                allocations.findByIdForUpdate(permit.getAllocationId())
                        .filter(a -> a.getStatus() == AllocationStatus.PENDING)
                        .ifPresent(a -> a.setStatus(AllocationStatus.ACTIVE));
            }
        } else if (delta.signum() < 0 && permit.getStatus() != PermitStatus.REVOKED) {
            if (permit.getAllocationId() != null) {
                Optional<ParkingAllocation> allocation = allocations.findByIdForUpdate(permit.getAllocationId())
                        .filter(a -> a.getStatus().live());
                allocation.ifPresent(a -> allocationService.end(a, AllocationStatus.REVOKED, "Permit payment refunded"));
            }
            allocationService.endPermit(permit, PermitStatus.REVOKED, "Permit payment refunded");
        }
    }

    private ParkingPayment newPayment(UUID societyId, PaymentPurpose purpose, UUID sessionId, UUID permitId, UUID quoteId,
                                      UUID flatId, String payerName, String vehicleNumber, BigDecimal amount,
                                      String currency, String description) {
        ParkingPayment payment = new ParkingPayment();
        payment.setSocietyId(societyId);
        payment.setPurpose(purpose);
        payment.setSessionId(sessionId);
        payment.setPermitId(permitId);
        payment.setQuoteId(quoteId);
        payment.setFlatId(flatId);
        payment.setPayerName(payerName == null ? null : truncate(payerName, 80));
        payment.setVehicleNumber(vehicleNumber);
        payment.setAmount(PricingCalculator.money(amount));
        payment.setCurrency(currency);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setDescription(description == null ? null : truncate(description, 200));
        UUID actor = currentUserOrNull();
        payment.setRecordedBy(actor);
        payment.setRecordedByName(actor == null ? "System" : support.displayName(actor));
        return payment;
    }

    private void markPaid(ParkingPayment payment, Instant at, String actorName) {
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(at);
        payment.setConfirmedBy(currentUserOrNull());
        payment.setConfirmedByName(actorName);
    }

    private void save(ParkingPayment payment) {
        try {
            payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.QUOTE_EXPIRED, "This charge has already been paid. Refresh the session.");
        }
    }

    private void event(ParkingPayment payment, PaymentEventType type, String reason, String actorName) {
        ParkingPaymentEvent event = new ParkingPaymentEvent();
        event.setPaymentId(payment.getId());
        event.setSocietyId(payment.getSocietyId());
        event.setEventType(type);
        event.setStatus(payment.getStatus());
        event.setAmount(payment.getAmount());
        event.setMethod(payment.getMethod());
        event.setReference(payment.getReference());
        event.setReason(reason == null ? null : truncate(reason.trim(), 200));
        event.setActorUserId(currentUserOrNull());
        event.setActorName(actorName);
        event.setOccurredAt(clock.instant());
        events.save(event);
    }

    private ParkingReceipt issueReceipt(ParkingPayment payment, ParkingQuote quote, String actorName) {
        ParkingReceipt receipt = new ParkingReceipt();
        receipt.setSocietyId(payment.getSocietyId());
        receipt.setPaymentId(payment.getId());
        Instant now = clock.instant();
        receipt.setReceiptNumber(support.receiptNumber(payment.getSocietyId(),
                now.atZone(support.zone(payment.getSocietyId())).getYear()));
        receipt.setIssuedAt(now);
        receipt.setPayerName(payment.getPayerName());
        receipt.setSocietyName(support.society(payment.getSocietyId()).getName());
        receipt.setVehicleNumber(payment.getVehicleNumber());
        receipt.setCurrency(payment.getCurrency());
        receipt.setIssuedByName(actorName);
        if (quote != null && quote.getTotal().compareTo(payment.getAmount()) == 0) {
            receipt.setLines(quote.getLines());
            receipt.setSubtotal(quote.getSubtotal());
            receipt.setTax(quote.getTax());
            receipt.setTaxLabel(quote.getTaxLabel());
        } else {
            receipt.setLines(charges.linesJson(List.of(new PricingCalculator.Line(
                    payment.getDescription() == null ? "Parking charges" : payment.getDescription(), payment.getAmount()))));
            receipt.setSubtotal(payment.getAmount());
            receipt.setTax(BigDecimal.ZERO.setScale(2));
        }
        receipt.setTotal(payment.getAmount());
        return receipts.saveAndFlush(receipt);
    }

    private void movement(ParkingSession session, MovementType type, UUID gateId, String actorName, String details) {
        ParkingMovement movement = new ParkingMovement();
        movement.setSocietyId(session.getSocietyId());
        movement.setSessionId(session.getId());
        movement.setVehicleNumber(session.getVehicleNumber());
        movement.setEventType(type);
        movement.setGateId(gateId);
        movement.setActorUserId(currentUserOrNull());
        movement.setActorName(actorName);
        movement.setDetails(truncate(details, 300));
        movement.setOccurredAt(clock.instant());
        movements.save(movement);
    }

    private void notifyPaid(ParkingPayment payment) {
        support.notifyFlat(payment.getFlatId(), payment.getSocietyId(), "Parking payment confirmed",
                "Payment for " + payment.getVehicleNumber() + " has been confirmed.",
                Notification.ReferenceType.PARKING_PAYMENT, payment.getId());
    }

    private ParkingSession lockSession(UUID id, UUID societyId) {
        ParkingSession session = sessions.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PARKING_SESSION_NOT_FOUND));
        access.ensureSameSociety(session.getSocietyId(), societyId, ErrorCode.PARKING_SESSION_NOT_FOUND);
        return session;
    }

    private Optional<ParkingSession> sessionOf(ParkingPayment payment) {
        return payment.getSessionId() == null ? Optional.empty() : sessions.findByIdForUpdate(payment.getSessionId());
    }

    private ParkingPayment lock(UUID id, UUID societyId) {
        ParkingPayment payment = payments.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND));
        access.ensureSameSociety(payment.getSocietyId(), societyId, ErrorCode.PAYMENT_NOT_FOUND);
        return payment;
    }

    private void requireVisible(ParkingPayment payment, ParkingAccess.Scope scope) {
        access.ensureSameSociety(payment.getSocietyId(), scope.societyId(), ErrorCode.PAYMENT_NOT_FOUND);
        if (!scope.canSeeFlat(payment.getFlatId()) || (scope.guard() && payment.getPurpose() != PaymentPurpose.SESSION)) {
            throw new ApiException(ErrorCode.PAYMENT_NOT_FOUND);
        }
    }

    /** Payer shown on receipts: the flat (for resident / visitor sessions) or the vehicle number (public parking). */
    private String payerName(ParkingSession session) {
        if (session.getFlatId() != null) {
            var flat = refLoader.refs().flat(session.getFlatId());
            if (flat != null) {
                return "Flat " + flat.label();
            }
        }
        return session.getVehicleNumber();
    }

    private UUID currentUserOrNull() {
        try {
            return access.currentUserId();
        } catch (ApiException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ================================================================ mapping

    private PaymentResponse toResponse(ParkingPayment p, boolean withHistory) {
        ParkingReceipt receipt = receipts.findByPaymentId(p.getId()).orElse(null);
        List<PaymentEventView> history = withHistory
                ? events.findByPaymentIdOrderByOccurredAtAsc(p.getId()).stream()
                        .map(e -> new PaymentEventView(e.getEventType(), e.getStatus(), Money.of(e.getAmount(), p.getCurrency()),
                                e.getMethod(), e.getReference(), e.getReason(), e.getActorName(), e.getOccurredAt()))
                        .toList()
                : List.of();
        return toResponse(p, receipt, history);
    }

    private PaymentResponse toResponse(ParkingPayment p, ParkingReceipt receipt, List<PaymentEventView> history) {
        return new PaymentResponse(p.getId(), p.getStatus(), Money.of(p.getAmount(), p.getCurrency()), p.getMethod(),
                p.getPurpose(), p.getSessionId(), p.getPermitId(), p.getVehicleNumber(), p.getDescription(),
                p.getReference(), p.getCreatedAt(), p.getPaidAt(), p.getRecordedByName(), p.getConfirmedByName(),
                p.getStatusReason(), receipt == null ? null : toReceipt(receipt), history);
    }

    private ReceiptResponse toReceipt(ParkingReceipt r) {
        List<PricingCalculator.Line> raw = charges.parseLines(r.getLines());
        List<QuoteLine> lines = raw.stream().map(l -> new QuoteLine(l.label(), Money.of(l.amount(), r.getCurrency()))).toList();
        return new ReceiptResponse(r.getId(), r.getPaymentId(), r.getReceiptNumber(), r.getIssuedAt(), r.getPayerName(),
                r.getSocietyName(), r.getVehicleNumber(), lines, Money.of(r.getSubtotal(), r.getCurrency()),
                Money.of(r.getTax(), r.getCurrency()), r.getTaxLabel(), Money.of(r.getTotal(), r.getCurrency()),
                r.getIssuedByName());
    }
}
