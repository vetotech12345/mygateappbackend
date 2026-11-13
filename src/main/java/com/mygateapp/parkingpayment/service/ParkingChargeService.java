package com.mygateapp.parkingpayment.service;

import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.parking.dto.ParkingRefs.Money;
import com.mygateapp.parking.entity.ParkingSession;
import com.mygateapp.parking.enums.ParkingEnums.SessionStatus;
import com.mygateapp.parking.repository.ParkingSessionRepository;
import com.mygateapp.parking.service.ParkingAccess;
import com.mygateapp.parking.service.ParkingSettingsStore;
import com.mygateapp.parkingpayment.dto.PaymentDtos.QuoteLine;
import com.mygateapp.parkingpayment.dto.PaymentDtos.QuoteRequest;
import com.mygateapp.parkingpayment.dto.PaymentDtos.QuoteResponse;
import com.mygateapp.parkingpayment.dto.PaymentDtos.RateResponse;
import com.mygateapp.parkingpayment.entity.ParkingQuote;
import com.mygateapp.parkingpayment.entity.ParkingRatePlan;
import com.mygateapp.parkingpayment.enums.PaymentEnums.BillingUnit;
import com.mygateapp.parkingpayment.repository.QuoteRepository;
import com.mygateapp.security.Permission;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Computes charges on the server and stores them as quotes. Clients only display quotes; a payment must reference
 * an unexpired quote whose total still equals the amount due.
 */
@Service
public class ParkingChargeService {

    static final Duration QUOTE_TTL = Duration.ofMinutes(10);
    static final Duration MAX_ESTIMATE = Duration.ofDays(31);
    static final Duration MAX_MONTHLY_ESTIMATE = Duration.ofDays(366);

    /** A session's charge at an instant. {@code outstanding} = total − settled (paid or waived), never negative. */
    public record SessionCharge(RateResponse rate, PricingCalculator.Charge charge, BigDecimal settled,
                                BigDecimal outstanding) {
    }

    private final QuoteRepository quotes;
    private final ParkingSessionRepository sessions;
    private final RatePlanService ratePlans;
    private final ParkingSettingsStore settingsStore;
    private final ParkingAccess access;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ParkingChargeService(QuoteRepository quotes, ParkingSessionRepository sessions, RatePlanService ratePlans,
                                ParkingSettingsStore settingsStore, ParkingAccess access, ObjectMapper objectMapper,
                                Clock clock) {
        this.quotes = quotes;
        this.sessions = sessions;
        this.ratePlans = ratePlans;
        this.settingsStore = settingsStore;
        this.access = access;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ================================================================= quotes

    @Transactional
    public QuoteResponse quote(QuoteRequest request) {
        if (request.sessionId() != null) {
            return sessionQuote(request.sessionId());
        }
        UUID societyId = access.requireViewer();
        if (request.rateId() == null) {
            throw ApiException.field("rateId", "rateId or sessionId is required");
        }
        ParkingRatePlan plan = ratePlans.requireApplicable(request.rateId(), societyId, request.vehicleType(), null, null);
        Instant from = request.from() == null ? clock.instant() : request.from();
        if (request.until() == null) {
            throw ApiException.field("until", "until is required for an estimate");
        }
        validateEstimateWindow(plan.getBillingUnit(), from, request.until());
        RateResponse rate = ratePlans.toResponse(plan);
        PricingCalculator.Charge charge = PricingCalculator.calculate(RatePlanService.terms(rate), from, request.until(),
                null, settingsStore.settingsFor(societyId).getFreeGraceMinutes());
        return toResponse(store(societyId, plan.getId(), null, null, from, request.until(), charge, BigDecimal.ZERO,
                rate.amount().currency()), rate);
    }

    /** The charge to close an active session now (guards, admins, or residents of the flat). */
    @Transactional
    public QuoteResponse sessionQuote(UUID sessionId) {
        ParkingAccess.Scope scope = access.readScope(Permission.PARKING_PAYMENT_MANAGE, true);
        ParkingSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.PARKING_SESSION_NOT_FOUND));
        access.ensureSameSociety(session.getSocietyId(), scope.societyId(), ErrorCode.PARKING_SESSION_NOT_FOUND);
        if (!scope.canSeeFlat(session.getFlatId())) {
            throw new ApiException(ErrorCode.PARKING_SESSION_NOT_FOUND);
        }
        if (!session.isActive()) {
            throw new ApiException(ErrorCode.SESSION_ALREADY_CLOSED);
        }
        if (!session.isPaid()) {
            throw new ApiException(ErrorCode.NOTHING_TO_PAY, "This is not a paid parking session.");
        }
        Instant now = clock.instant();
        SessionCharge charge = charge(session, now);
        ParkingQuote quote = store(session.getSocietyId(), session.getRatePlanId(), session.getId(), null,
                session.getEntryAt(), now, charge.charge(), charge.settled(), session.getCurrency());
        return toResponse(quote, charge.rate());
    }

    /** Fee for a paid permit window (monthly plans), stored for the permit's pending payment. */
    public ParkingQuote permitQuote(UUID societyId, ParkingRatePlan plan, UUID permitId, Instant from, Instant until) {
        RateResponse rate = ratePlans.toResponse(plan);
        PricingCalculator.Charge charge = PricingCalculator.calculate(RatePlanService.terms(rate), from, until, null, 0);
        return store(societyId, plan.getId(), null, permitId, from, until, charge, BigDecimal.ZERO, plan.getCurrency());
    }

    // ======================================================= session charges

    /** Charge of a paid session at {@code at} (or at its exit), using the rate snapshot taken at entry. */
    public SessionCharge charge(ParkingSession session, Instant at) {
        if (!session.isPaid()) {
            return null;
        }
        RateResponse rate = ratePlans.fromSnapshot(session.getRateSnapshot());
        Instant end = session.getExitAt() != null ? session.getExitAt() : at;
        PricingCalculator.Charge charge = PricingCalculator.calculate(RatePlanService.terms(rate), session.getEntryAt(),
                end, session.getValidUntil(), settingsStore.settingsFor(session.getSocietyId()).getFreeGraceMinutes());
        BigDecimal due = session.getStatus() == SessionStatus.ACTIVE || session.getFinalAmount() == null
                ? charge.total() : session.getFinalAmount();
        BigDecimal outstanding = due.subtract(session.getPaidAmount()).max(BigDecimal.ZERO);
        return new SessionCharge(rate, charge, session.getPaidAmount(), PricingCalculator.money(outstanding));
    }

    public ParkingQuote requireQuote(UUID quoteId, UUID societyId) {
        ParkingQuote quote = quotes.findById(quoteId).orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_FOUND));
        access.ensureSameSociety(quote.getSocietyId(), societyId, ErrorCode.QUOTE_NOT_FOUND);
        return quote;
    }

    public List<PricingCalculator.Line> parseLines(String json) {
        return objectMapper.readValue(json, new TypeReference<List<PricingCalculator.Line>>() { });
    }

    public List<QuoteLine> lines(ParkingQuote quote) {
        List<PricingCalculator.Line> raw = parseLines(quote.getLines());
        return raw.stream().map(l -> new QuoteLine(l.label(), Money.of(l.amount(), quote.getCurrency()))).toList();
    }

    public String linesJson(List<PricingCalculator.Line> lines) {
        return objectMapper.writeValueAsString(lines);
    }

    public QuoteResponse toResponse(ParkingQuote q, RateResponse rate) {
        return new QuoteResponse(q.getId(), rate, q.getSessionId(), q.getPermitId(), q.getPeriodFrom(), q.getPeriodUntil(),
                q.getBillableMinutes(), lines(q), Money.of(q.getSubtotal(), q.getCurrency()),
                Money.of(q.getTax(), q.getCurrency()), q.getTaxLabel(), Money.of(q.getAlreadyPaid(), q.getCurrency()),
                Money.of(q.getTotal(), q.getCurrency()), q.getCalculatedAt(), q.getExpiresAt());
    }

    // ================================================================ helpers

    private ParkingQuote store(UUID societyId, UUID ratePlanId, UUID sessionId, UUID permitId, Instant from, Instant until,
                               PricingCalculator.Charge charge, BigDecimal alreadyPaid, String currency) {
        Instant now = clock.instant();
        List<PricingCalculator.Line> lines = new ArrayList<>(charge.lines());
        if (alreadyPaid.signum() > 0) {
            lines.add(new PricingCalculator.Line("Paid earlier", PricingCalculator.money(alreadyPaid.negate())));
        }
        ParkingQuote quote = new ParkingQuote();
        quote.setSocietyId(societyId);
        quote.setRatePlanId(ratePlanId);
        quote.setSessionId(sessionId);
        quote.setPermitId(permitId);
        quote.setPeriodFrom(from);
        quote.setPeriodUntil(until);
        quote.setBillableMinutes((int) Math.min(Integer.MAX_VALUE, charge.billableMinutes()));
        quote.setLines(linesJson(lines));
        quote.setSubtotal(charge.subtotal());
        quote.setTax(charge.tax());
        quote.setTaxLabel(charge.taxLabel());
        quote.setAlreadyPaid(PricingCalculator.money(alreadyPaid));
        quote.setTotal(PricingCalculator.money(charge.total().subtract(alreadyPaid).max(BigDecimal.ZERO)));
        quote.setCurrency(currency);
        quote.setCalculatedAt(now);
        quote.setExpiresAt(now.plus(QUOTE_TTL));
        quote.setCreatedBy(currentUserOrNull());
        return quotes.saveAndFlush(quote);
    }

    private static void validateEstimateWindow(BillingUnit unit, Instant from, Instant until) {
        if (!until.isAfter(from)) {
            throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "until", "The end time must be after the start time.");
        }
        Duration max = unit == BillingUnit.MONTHLY ? MAX_MONTHLY_ESTIMATE : MAX_ESTIMATE;
        if (Duration.between(from, until).compareTo(max) > 0) {
            throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "until", "This window is too long for an estimate.");
        }
    }

    private UUID currentUserOrNull() {
        try {
            return access.currentUserId();
        } catch (ApiException e) {
            return null;
        }
    }
}
