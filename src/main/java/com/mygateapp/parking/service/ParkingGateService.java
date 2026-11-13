package com.mygateapp.parking.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.Strings;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.guards.entity.SocietyGate;
import com.mygateapp.guards.repository.GateRepository;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.parking.dto.ParkingDtos.EntryRequest;
import com.mygateapp.parking.dto.ParkingDtos.SessionResponse;
import com.mygateapp.parking.dto.ParkingDtos.VerificationResponse;
import com.mygateapp.parking.dto.ParkingDtos.VerificationVisitor;
import com.mygateapp.parking.dto.ParkingDtos.VerifyRequest;
import com.mygateapp.parking.entity.ParkingAllocation;
import com.mygateapp.parking.entity.ParkingMovement;
import com.mygateapp.parking.entity.ParkingPermit;
import com.mygateapp.parking.entity.ParkingRequest;
import com.mygateapp.parking.entity.ParkingSession;
import com.mygateapp.parking.entity.ParkingSlot;
import com.mygateapp.parking.entity.ParkingVerification;
import com.mygateapp.parking.entity.SocietyParkingSettings;
import com.mygateapp.parking.enums.ParkingEnums.AllocationStatus;
import com.mygateapp.parking.enums.ParkingEnums.AllocationType;
import com.mygateapp.parking.enums.ParkingEnums.ChargeStatus;
import com.mygateapp.parking.enums.ParkingEnums.MovementType;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.enums.ParkingEnums.PermitType;
import com.mygateapp.parking.enums.ParkingEnums.SessionStatus;
import com.mygateapp.parking.enums.ParkingEnums.VerificationDecision;
import com.mygateapp.parking.enums.ParkingEnums.VisitorParkingType;
import com.mygateapp.parking.repository.ParkingAllocationRepository;
import com.mygateapp.parking.repository.ParkingMovementRepository;
import com.mygateapp.parking.repository.ParkingPermitRepository;
import com.mygateapp.parking.repository.ParkingRequestRepository;
import com.mygateapp.parking.repository.ParkingSessionRepository;
import com.mygateapp.parking.repository.ParkingVerificationRepository;
import com.mygateapp.parkingpayment.dto.PaymentDtos.RateResponse;
import com.mygateapp.parkingpayment.entity.ParkingRatePlan;
import com.mygateapp.parkingpayment.enums.PaymentEnums.BillingUnit;
import com.mygateapp.parkingpayment.enums.PaymentEnums.RateAppliesTo;
import com.mygateapp.parkingpayment.service.ParkingChargeService;
import com.mygateapp.parkingpayment.service.ParkingPaymentService;
import com.mygateapp.parkingpayment.service.RatePlanService;
import com.mygateapp.security.GuardPermission;
import com.mygateapp.security.Permission;
import com.mygateapp.security.UserAccess;
import com.mygateapp.vehicle.VehicleNumbers;
import com.mygateapp.vehicle.entity.Vehicle;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import com.mygateapp.vehicle.repository.VehicleRepository;
import com.mygateapp.visitors.entity.Visitor;
import com.mygateapp.visitors.enums.VisitorEnums.ApprovalStatus;
import com.mygateapp.visitors.service.VisitorService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gate operations for vehicles: verification → entry → (payment) → exit.
 *
 * <ul>
 *   <li>A vehicle number alone never authorises entry: entry consumes a fresh, single-use verification of the same
 *       guard and society.</li>
 *   <li>One active session per vehicle (advisory lock + partial unique index) and per slot (row lock + index).</li>
 *   <li>Server timestamps only; every entry, exit and override is an immutable movement plus an audit entry.</li>
 *   <li>Visitor approval stays in the visitor module: a linked visitor that is not approved blocks entry; an approved
 *       one is checked in/out together with the vehicle when the guard also holds VISITOR_ENTRY.</li>
 * </ul>
 */
@Service
public class ParkingGateService {

    static final Duration VERIFICATION_TTL = Duration.ofMinutes(5);

    private final ParkingSessionRepository sessions;
    private final ParkingMovementRepository movements;
    private final ParkingVerificationRepository verifications;
    private final ParkingPermitRepository permits;
    private final ParkingAllocationRepository allocations;
    private final ParkingRequestRepository requests;
    private final VehicleRepository vehicles;
    private final GateRepository gates;
    private final VisitorService visitorService;
    private final ParkingRequestService requestService;
    private final ParkingSessionQueryService queries;
    private final ParkingInventoryService inventory;
    private final ParkingAllocationService allocationService;
    private final RatePlanService ratePlans;
    private final ParkingChargeService charges;
    private final ParkingPaymentService payments;
    private final ParkingSettingsStore settingsStore;
    private final ParkingAccess access;
    private final ParkingSupport support;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ParkingGateService(ParkingSessionRepository sessions, ParkingMovementRepository movements,
                              ParkingVerificationRepository verifications, ParkingPermitRepository permits,
                              ParkingAllocationRepository allocations, ParkingRequestRepository requests,
                              VehicleRepository vehicles, GateRepository gates, VisitorService visitorService,
                              ParkingRequestService requestService, ParkingSessionQueryService queries,
                              ParkingInventoryService inventory, ParkingAllocationService allocationService,
                              RatePlanService ratePlans, ParkingChargeService charges, ParkingPaymentService payments,
                              ParkingSettingsStore settingsStore, ParkingAccess access, ParkingSupport support,
                              AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.sessions = sessions;
        this.movements = movements;
        this.verifications = verifications;
        this.permits = permits;
        this.allocations = allocations;
        this.requests = requests;
        this.vehicles = vehicles;
        this.gates = gates;
        this.visitorService = visitorService;
        this.requestService = requestService;
        this.queries = queries;
        this.inventory = inventory;
        this.allocationService = allocationService;
        this.ratePlans = ratePlans;
        this.charges = charges;
        this.payments = payments;
        this.settingsStore = settingsStore;
        this.access = access;
        this.support = support;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // =========================================================== verification

    /** Internal result of the gate decision for one vehicle number. */
    private static final class Decision {
        VerificationDecision decision = VerificationDecision.NOT_REGISTERED;
        Category category;
        VehicleType vehicleType;
        Vehicle vehicle;
        UUID flatId;
        ParkingPermit permit;
        UUID slotId;
        Instant validUntil;
        Visitor visitor;
        ParkingRequest request;
        ParkingSession activeSession;
        boolean paidOffered;
        RateAppliesTo audience = RateAppliesTo.PUBLIC;
        String summary;
        final List<String> reasons = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        void allow(Category category, String summary) {
            this.decision = VerificationDecision.ALLOWED;
            this.category = category;
            this.summary = summary;
        }

        void deny(String reason) {
            this.decision = VerificationDecision.DENIED;
            this.reasons.add(reason);
        }
    }

    @Transactional
    public VerificationResponse verify(VerifyRequest request) {
        UserAccess.GuardAccess guard = access.requireGateGuard();
        UUID societyId = guard.societyId();
        String number = VehicleNumbers.requireLookup(request.vehicleNumber(), "vehicleNumber");
        SocietyParkingSettings settings = settingsStore.settingsFor(societyId);
        Instant now = clock.instant();
        Decision d = decide(societyId, guard, number, request.vehicleType(), settings, now);

        ParkingVerification v = new ParkingVerification();
        v.setSocietyId(societyId);
        v.setGuardUserId(access.currentUserId());
        v.setGateId(guard.gateId());
        v.setVehicleNumber(number);
        v.setVehicleType(d.vehicleType);
        v.setDecision(d.decision);
        v.setCategory(d.category);
        v.setVehicleId(d.vehicle == null ? null : d.vehicle.getId());
        v.setVisitorId(d.visitor == null ? null : d.visitor.getId());
        v.setRequestId(d.request == null ? null : d.request.getId());
        v.setPermitId(d.permit == null ? null : d.permit.getId());
        v.setSlotId(d.slotId);
        v.setFlatId(d.flatId);
        v.setValidUntil(d.validUntil);
        v.setPaidParkingOffered(d.paidOffered);
        v.setSummary(d.summary == null ? null : ParkingAllocationService.truncate(d.summary, 200));
        v.setReasons(d.reasons);
        v.setCreatedAt(now);
        v.setExpiresAt(now.plus(VERIFICATION_TTL));
        verifications.saveAndFlush(v);

        ParkingRefLoader.Refs refs = refsFor();
        List<RateResponse> rates = d.paidOffered
                ? ratePlans.applicable(societyId, d.vehicleType, d.audience).stream().map(ratePlans::toResponse).toList()
                : List.of();
        VerificationVisitor visitor = d.visitor == null ? null
                : new VerificationVisitor(d.visitor.getId(), d.visitor.getName(), d.visitor.displayStatus(),
                d.visitor.getFlat().toRef());
        return new VerificationResponse(v.getId(), number, d.vehicleType, d.decision, d.category,
                d.summary == null ? summaryOf(d) : d.summary, ParkingRefLoader.vehicleRef(d.vehicle), refs.flat(d.flatId),
                ParkingRefLoader.permitRef(d.permit), refs.slot(d.slotId), d.validUntil, visitor,
                d.request == null ? null : d.request.getId(),
                d.activeSession == null ? null : queries.toResponse(d.activeSession, false), d.paidOffered, rates,
                d.reasons, d.warnings, settings.isEmergencyOverrideAllowed(), v.getExpiresAt());
    }

    private Decision decide(UUID societyId, UserAccess.GuardAccess guard, String number, VehicleType typedType,
                            SocietyParkingSettings settings, Instant now) {
        Decision d = new Decision();
        d.vehicleType = typedType;
        SocietyGate gate = gates.findById(guard.gateId()).orElseThrow(() -> new ApiException(ErrorCode.GUARD_NO_GATE));
        if (gate.getGateType() == SocietyGate.GateType.PEDESTRIAN) {
            d.deny("Vehicles cannot enter through " + gate.getName() + ".");
            d.summary = "Wrong gate";
            return d;
        }
        Optional<ParkingSession> active = sessions.findBySocietyIdAndVehicleNumberAndStatus(societyId, number, SessionStatus.ACTIVE);
        if (active.isPresent()) {
            d.decision = VerificationDecision.ALREADY_INSIDE;
            d.activeSession = active.get();
            d.category = active.get().getCategory();
            d.vehicleType = active.get().getVehicleType();
            d.summary = "Already inside since " + active.get().getEntryAt();
            return d;
        }
        Vehicle vehicle = vehicles.findBySocietyIdAndRegistrationNumberAndActiveTrue(societyId, number).orElse(null);
        List<ParkingPermit> open = permits.findOpenByNumber(societyId, number);
        ParkingPermit validPermit = open.stream().filter(p -> p.isValidAt(now)).findFirst().orElse(null);
        if (vehicle != null) {
            d.vehicle = vehicle;
            d.vehicleType = vehicle.getVehicleType();
            d.flatId = vehicle.getFlatId();
            d.category = vehicle.getCategory();
            d.audience = vehicle.getCategory() == Category.RESIDENT ? RateAppliesTo.RESIDENT : RateAppliesTo.PUBLIC;
        }
        if (d.vehicleType != null && !ParkingSettingsStore.allowedTypes(settings).contains(d.vehicleType)) {
            d.deny("This vehicle type is not allowed in the society.");
            d.summary = "Vehicle type not allowed";
            return d;
        }
        Optional<ParkingRequest> visitorRequest = requestService.usableVisitorRequest(societyId, number, now);

        if (vehicle != null && visitorRequest.isEmpty()) {
            String label = vehicle.getCategory() == Category.RESIDENT && vehicle.getFlatId() != null
                    ? "Registered resident vehicle · " + queries.refs().flat(vehicle.getFlatId()).label()
                    : "Registered " + vehicle.getCategory().name().toLowerCase() + " vehicle";
            if (validPermit != null && (vehicle.isApproved() || validPermit.getVehicleId() == null)) {
                applyPermit(d, validPermit);
                d.allow(categoryFor(validPermit.getPermitType(), vehicle.getCategory()), label);
                return d;
            }
            if (!vehicle.isApproved()) {
                d.deny(switch (vehicle.getRegistrationStatus()) {
                    case NOT_SUBMITTED, PENDING -> "Vehicle registration is waiting for approval by the society office.";
                    case REJECTED -> "Vehicle registration was rejected.";
                    case SUSPENDED -> "Vehicle registration is suspended.";
                    case APPROVED -> "Vehicle is not approved.";
                });
            } else {
                Optional<ParkingAllocation> allocation = allocations.findLiveByVehicle(vehicle.getId()).stream()
                        .filter(a -> a.getStatus() == AllocationStatus.ACTIVE && a.coversNow(now)).findFirst();
                if (allocation.isPresent()) {
                    d.slotId = allocation.get().getSlotId();
                    d.validUntil = allocation.get().getValidUntil();
                    d.allow(vehicle.getCategory(), label);
                    return d;
                }
                if (vehicle.getCategory() == Category.RESIDENT && !settings.isResidentEntryRequiresPermit()) {
                    d.allow(Category.RESIDENT, label);
                    d.warnings.add("No parking slot is allotted to this vehicle.");
                    return d;
                }
                open.stream().filter(p -> p.getStatus() != PermitStatus.ACTIVE).findFirst().ifPresentOrElse(
                        p -> d.reasons.add("Permit " + p.getPermitNumber() + " is " + p.getStatus().name().toLowerCase().replace('_', ' ') + "."),
                        () -> d.reasons.add("No active parking permit for this vehicle."));
                d.decision = VerificationDecision.DENIED;
            }
            d.summary = label;
            offerPaid(d, societyId, settings, now, true);
            return d;
        }

        if (visitorRequest.isPresent()) {
            ParkingRequest r = visitorRequest.get();
            d.request = r;
            d.flatId = r.getFlatId();
            d.vehicleType = r.getVehicleType();
            d.slotId = r.getAssignedSlotId();
            d.validUntil = r.getValidUntil();
            d.permit = r.getPermitId() == null ? null : permits.findById(r.getPermitId()).orElse(null);
            d.audience = RateAppliesTo.VISITOR;
            if (r.getVisitorId() != null) {
                Visitor visitor = queries.findVisitorById(r.getVisitorId());
                d.visitor = visitor;
                if (visitor != null && visitor.getApprovalStatus() == ApprovalStatus.PENDING) {
                    d.decision = VerificationDecision.NEEDS_VISITOR_APPROVAL;
                    d.summary = "Visitor is waiting for the resident's approval";
                    return d;
                }
                if (visitor != null && visitor.getApprovalStatus() != ApprovalStatus.APPROVED) {
                    d.deny("The linked visit is " + visitor.getApprovalStatus().name().toLowerCase() + ".");
                    d.summary = "Visit not approved";
                    return d;
                }
            }
            d.allow(r.getParkingType() == VisitorParkingType.PAID ? Category.PAID : Category.VISITOR,
                    "Visitor parking " + r.getReferenceNumber() + " for " + r.getVisitorName());
            if (r.getValidFrom().isAfter(now)) {
                d.warnings.add("Arrived before the booked time.");
            }
            return d;
        }

        if (validPermit != null) {
            applyPermit(d, validPermit);
            d.vehicleType = validPermit.getVehicleType();
            d.flatId = validPermit.getFlatId();
            d.allow(categoryFor(validPermit.getPermitType(), null), "Permit " + validPermit.getPermitNumber());
            return d;
        }

        Visitor visitor = queries.findVisitorByVehicle(societyId, number, now);
        if (visitor != null) {
            d.visitor = visitor;
            d.flatId = visitor.getFlat().getId();
            d.audience = RateAppliesTo.VISITOR;
            d.category = Category.VISITOR;
            if (visitor.getApprovalStatus() == ApprovalStatus.PENDING) {
                d.decision = VerificationDecision.NEEDS_VISITOR_APPROVAL;
                d.summary = "Visitor is waiting for the resident's approval";
                return d;
            }
            boolean visitValid = !visitor.isInvitation() || visitor.isWithinWindow(now);
            if (!visitValid) {
                d.deny("The visitor's invite is not valid at this time.");
            } else if (!settings.isVisitorParkingEnabled()) {
                d.deny("Visitor parking is not available in this society.");
            } else if (!settings.isVisitorParkingAutoApprove()) {
                d.deny("No visitor parking was booked for this vehicle.");
            } else {
                Instant until = now.plus(Duration.ofHours(settings.getMaxVisitorParkingHours()));
                Optional<ParkingSlot> free = inventory.compute(societyId, ParkingUsage.VISITOR.servedBy(), d.vehicleType,
                        now, until, null).free().stream().findFirst();
                if (free.isPresent()) {
                    d.slotId = free.get().getId();
                    d.validUntil = until;
                    d.allow(Category.VISITOR, "Approved visitor · " + visitor.getFlat().toRef().label());
                    return d;
                }
                d.deny("No visitor parking slot is free.");
            }
            d.summary = "Visitor · " + visitor.getFlat().toRef().label();
            offerPaid(d, societyId, settings, now, true);
            return d;
        }

        d.decision = VerificationDecision.NOT_REGISTERED;
        d.summary = "Vehicle not registered with the society";
        offerPaid(d, societyId, settings, now, settings.isExternalPaidParkingAllowed());
        return d;
    }

    private void applyPermit(Decision d, ParkingPermit permit) {
        d.permit = permit;
        d.slotId = permit.getSlotId();
        d.validUntil = permit.getValidUntil();
        if (permit.getFlatId() != null) {
            d.flatId = permit.getFlatId();
        }
        if (d.vehicleType == null) {
            d.vehicleType = permit.getVehicleType();
        }
    }

    /**
     * Offers paid parking when the society allows it for this audience, a paid slot is free and a rate applies. An
     * unregistered vehicle's type is unknown here (the guard gives it at entry), so any type is considered.
     */
    private void offerPaid(Decision d, UUID societyId, SocietyParkingSettings settings, Instant now, boolean audienceAllowed) {
        if (!settings.isPaidParkingEnabled() || !audienceAllowed) {
            return;
        }
        boolean freeSlot = !inventory.compute(societyId, ParkingUsage.PAID.servedBy(), d.vehicleType, now,
                now.plus(Duration.ofHours(1)), null).free().isEmpty();
        d.paidOffered = freeSlot && !ratePlans.applicable(societyId, d.vehicleType, d.audience).isEmpty();
    }

    // ================================================================== entry

    @Transactional
    public SessionResponse enter(EntryRequest request) {
        UserAccess.GuardAccess guard = access.requireGateGuard();
        UUID societyId = guard.societyId();
        UUID guardUserId = access.currentUserId();
        Instant now = clock.instant();
        String number = VehicleNumbers.requireLookup(request.vehicleNumber(), "vehicleNumber");
        if (verifications.consume(request.verificationId(), guardUserId, societyId, now) != 1) {
            throw new ApiException(ErrorCode.VERIFICATION_EXPIRED);
        }
        ParkingVerification v = verifications.findById(request.verificationId())
                .orElseThrow(() -> new ApiException(ErrorCode.VERIFICATION_EXPIRED));
        if (!v.getVehicleNumber().equals(number)) {
            throw new ApiException(ErrorCode.VERIFICATION_EXPIRED, "This verification was for another vehicle. Verify again.");
        }
        lockVehicle(societyId, number);
        if (sessions.findBySocietyIdAndVehicleNumberAndStatus(societyId, number, SessionStatus.ACTIVE).isPresent()) {
            throw new ApiException(ErrorCode.SESSION_ALREADY_ACTIVE);
        }
        SocietyParkingSettings settings = settingsStore.settingsFor(societyId);
        Category entryType = request.entryType();
        String overrideReason = Strings.trimToNull(request.overrideReason());
        boolean paidPath = false;
        boolean override = false;
        switch (v.getDecision()) {
            case ALLOWED -> {
                if (entryType == Category.PAID && v.getCategory() != Category.PAID) {
                    if (!v.isPaidParkingOffered()) {
                        throw new ApiException(ErrorCode.ENTRY_NOT_AUTHORIZED, "Paid parking was not offered for this vehicle.");
                    }
                    paidPath = true;
                } else if (entryType != v.getCategory()) {
                    throw new ApiException(ErrorCode.ENTRY_NOT_AUTHORIZED,
                            "Record this entry as " + v.getCategory().name() + ".");
                }
            }
            case DENIED, NOT_REGISTERED -> {
                if (entryType == Category.PAID && v.isPaidParkingOffered()) {
                    paidPath = true;
                } else if (entryType == Category.EMERGENCY && settings.isEmergencyOverrideAllowed() && overrideReason != null) {
                    override = true;
                } else {
                    throw new ApiException(ErrorCode.ENTRY_NOT_AUTHORIZED,
                            v.getReasons().isEmpty() ? null : v.getReasons().getFirst());
                }
            }
            case NEEDS_VISITOR_APPROVAL -> throw new ApiException(ErrorCode.ENTRY_NOT_AUTHORIZED,
                    "The visitor has not been approved by the resident yet.");
            case ALREADY_INSIDE -> throw new ApiException(ErrorCode.SESSION_ALREADY_ACTIVE);
        }
        // The Flutter entry body carries no vehicle type; for unregistered vehicles derive it from the chosen rate or
        // slot, and default to CAR.
        VehicleType vehicleType = v.getVehicleType() != null ? v.getVehicleType() : request.vehicleType();
        if (vehicleType == null && paidPath && request.rateId() != null) {
            vehicleType = ratePlans.require(request.rateId(), societyId).getVehicleType();
        }
        if (vehicleType == null && request.slotId() != null) {
            vehicleType = inventory.requireSlot(request.slotId(), societyId).getVehicleType();
        }
        if (vehicleType == null) {
            vehicleType = VehicleType.CAR;
        }
        ParkingSettingsStore.requireAllowedType(settings, vehicleType, "vehicleType");
        ParkingRequest visitorRequest = v.getRequestId() == null ? null : requests.findByIdForUpdate(v.getRequestId()).orElse(null);
        if (visitorRequest != null && visitorRequest.getConsumedAt() != null) {
            throw new ApiException(ErrorCode.VERIFICATION_EXPIRED, "This visitor parking pass was already used.");
        }

        ParkingSession session = new ParkingSession();
        session.setSocietyId(societyId);
        session.setVehicleNumber(number);
        session.setVehicleType(vehicleType);
        session.setCategory(entryType);
        session.setVehicleId(v.getVehicleId());
        session.setVisitorId(v.getVisitorId());
        session.setFlatId(v.getFlatId());
        session.setPermitId(paidPath ? null : v.getPermitId());
        session.setRequestId(v.getRequestId());
        session.setVerificationId(v.getId());
        session.setCurrency(settings.getCurrency());
        session.setEntryGateId(guard.gateId());
        session.setEntryAt(now);
        session.setEntryRecordedBy(guardUserId);
        session.setValidUntil(paidPath ? null : v.getValidUntil());
        session.setEmergencyOverride(override);
        session.setOverrideReason(override ? overrideReason : null);

        ParkingRatePlan plan = null;
        if (paidPath) {
            if (request.rateId() == null) {
                throw ApiException.field("rateId", "Choose a rate for paid parking.");
            }
            plan = ratePlans.requireApplicable(request.rateId(), societyId, vehicleType, audienceOf(v), null);
            if (plan.getBillingUnit() == BillingUnit.MONTHLY) {
                throw new ApiException(ErrorCode.RATE_NOT_APPLICABLE, "Monthly rates are for permits.");
            }
        } else if (visitorRequest != null && visitorRequest.getRatePlanId() != null) {
            plan = ratePlans.require(visitorRequest.getRatePlanId(), societyId);
        }
        if (plan != null) {
            session.setRatePlanId(plan.getId());
            session.setRateSnapshot(ratePlans.snapshot(plan));
            session.setPaymentStatus(ChargeStatus.PENDING);
        }

        // Slot: the guard's choice, else the authorised slot, else a free slot for paid / visitor entries.
        Set<ParkingUsage> usages = ParkingUsage.servingCategory(paidPath ? Category.PAID : entryType);
        ParkingSlot slot = null;
        UUID authorisedSlot = paidPath ? null : v.getSlotId();
        if (request.slotId() != null) {
            slot = inventory.lockSlot(request.slotId(), societyId);
            if (!request.slotId().equals(authorisedSlot)) {
                inventory.requireSuitable(slot, override ? null : usages, vehicleType);
                requireNotReservedForOthers(slot, now, v);
            } else {
                inventory.requireSuitable(slot, null, vehicleType);
            }
            if (inventory.isOccupied(slot.getId())) {
                throw new ApiException(ErrorCode.SLOT_OCCUPIED);
            }
        } else if (authorisedSlot != null) {
            ParkingSlot candidate = inventory.lockSlot(authorisedSlot, societyId);
            if (candidate.isInService() && !inventory.isOccupied(candidate.getId())) {
                slot = candidate;
            }
        } else if (paidPath || (entryType == Category.VISITOR && visitorRequest == null && !override)) {
            Instant until = session.getValidUntil() != null ? session.getValidUntil() : now.plus(Duration.ofHours(1));
            slot = inventory.findAndLockFreeSlot(societyId, usages, vehicleType, now, until, null)
                    .orElseThrow(() -> new ApiException(ErrorCode.PARKING_UNAVAILABLE));
        }
        session.setSlotId(slot == null ? null : slot.getId());
        if (visitorRequest != null) {
            session.setAllocationId(visitorRequest.getAllocationId());
        } else if (v.getPermitId() != null && !paidPath) {
            permits.findById(v.getPermitId()).map(ParkingPermit::getAllocationId).ifPresent(session::setAllocationId);
        }
        try {
            sessions.saveAndFlush(session);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.SLOT_OCCUPIED, "The vehicle or slot is already in use. Refresh and try again.");
        }
        if (visitorRequest != null) {
            visitorRequest.setConsumedAt(now);
        }
        String actor = support.displayName(guardUserId);
        movement(session, override ? MovementType.ENTRY_OVERRIDE : MovementType.ENTRY, guard.gateId(), actor,
                override ? "Emergency override: " + overrideReason
                        : slot == null ? null : "Slot " + slot.getSlotNumber());
        checkInVisitor(session, guard);
        audit.record(societyId, override ? "PARKING_ENTRY_OVERRIDE" : "PARKING_ENTRY", "PARKING_SESSION", session.getId(),
                "Vehicle " + number + " entered (" + entryType + ")",
                ParkingAllocationService.details("slot", slot == null ? null : slot.getSlotNumber(),
                        "verificationId", v.getId(), "paid", plan != null, "overrideReason", override ? overrideReason : null));
        if (override) {
            support.notifyAdmins(societyId, "Emergency entry recorded",
                    number + " entered under an emergency override. Review the gate log.",
                    Notification.ReferenceType.PARKING_SESSION, session.getId());
        }
        if (session.getFlatId() != null && (entryType == Category.VISITOR || entryType == Category.PAID)) {
            support.notifyFlat(session.getFlatId(), societyId,
                    entryType == Category.PAID ? "Paid parking started" : "Visitor vehicle arrived",
                    number + " has entered" + (slot == null ? "." : " and is parked at " + slot.getSlotNumber() + "."),
                    Notification.ReferenceType.PARKING_SESSION, session.getId());
        }
        return queries.toResponse(session, true);
    }

    // =================================================================== exit

    @Transactional
    public SessionResponse close(UUID sessionId) {
        UserAccess.GuardAccess guard = access.requireGateGuard();
        ParkingSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.PARKING_SESSION_NOT_FOUND));
        access.ensureSameSociety(session.getSocietyId(), guard.societyId(), ErrorCode.PARKING_SESSION_NOT_FOUND);
        if (!session.isActive()) {
            throw new ApiException(ErrorCode.SESSION_ALREADY_CLOSED);
        }
        Instant now = clock.instant();
        SocietyParkingSettings settings = settingsStore.settingsFor(session.getSocietyId());
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal finalAmount = null;
        if (session.isPaid()) {
            ParkingChargeService.SessionCharge charge = charges.charge(session, now);
            boolean withinPaid = session.getPaidUntil() != null && !now.isAfter(session.getPaidUntil());
            finalAmount = withinPaid && charge.outstanding().signum() > 0 ? session.getPaidAmount() : charge.charge().total();
            outstanding = finalAmount.subtract(session.getPaidAmount()).max(BigDecimal.ZERO);
            if (outstanding.signum() > 0 && settings.isPaymentRequiredBeforeExit()) {
                throw new ApiException(ErrorCode.PAYMENT_PENDING);
            }
        }
        // Server time; never before entry (clock skew between nodes).
        Instant exitAt = now.isBefore(session.getEntryAt()) ? session.getEntryAt() : now;
        session.setStatus(SessionStatus.COMPLETED);
        session.setExitAt(exitAt);
        session.setExitGateId(guard.gateId());
        session.setExitRecordedBy(access.currentUserId());
        session.setFinalAmount(finalAmount);
        sessions.saveAndFlush(session);
        if (outstanding.signum() > 0) {
            payments.createPendingForSession(session, outstanding, "Unpaid parking charges at exit");
        }
        payments.refreshSessionStatus(session);
        String actor = support.displayName(access.currentUserId());
        movement(session, MovementType.EXIT, guard.gateId(), actor,
                outstanding.signum() > 0 ? "Exited with charges outstanding" : null);
        releaseTemporary(session);
        checkOutVisitor(session, guard);
        audit.record(session.getSocietyId(), "PARKING_EXIT", "PARKING_SESSION", session.getId(),
                "Vehicle " + session.getVehicleNumber() + " exited",
                ParkingAllocationService.details("final", finalAmount == null ? null : finalAmount.toPlainString(),
                        "outstanding", outstanding.signum() > 0 ? outstanding.toPlainString() : null));
        if (session.getFlatId() != null && session.getCategory() != Category.RESIDENT) {
            support.notifyFlat(session.getFlatId(), session.getSocietyId(), "Parking session closed",
                    session.getVehicleNumber() + " has left the premises.", Notification.ReferenceType.PARKING_SESSION,
                    session.getId());
        }
        return queries.toResponse(session, true);
    }

    /** Admin correction of a wrong entry. The session stays in history as CANCELLED. */
    @Transactional
    public SessionResponse cancel(UUID sessionId, String reason) {
        UUID societyId = access.requireAdmin(Permission.PARKING_LOG_VIEW);
        access.core().require(societyId, Permission.PARKING_ALLOCATION_MANAGE);
        ParkingSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.PARKING_SESSION_NOT_FOUND));
        access.ensureSameSociety(session.getSocietyId(), societyId, ErrorCode.PARKING_SESSION_NOT_FOUND);
        if (!session.isActive()) {
            throw new ApiException(ErrorCode.SESSION_ALREADY_CLOSED);
        }
        session.setStatus(SessionStatus.CANCELLED);
        session.setCancelReason(reason.trim());
        sessions.saveAndFlush(session);
        movement(session, MovementType.CANCELLED, null, support.displayName(access.currentUserId()), reason.trim());
        releaseTemporary(session);
        audit.record(societyId, "PARKING_SESSION_CANCELLED", "PARKING_SESSION", sessionId,
                "Session of " + session.getVehicleNumber() + " cancelled", Map.of("reason", reason));
        return queries.toResponse(session, true);
    }

    // ================================================================ helpers

    /** Serialises entries of the same vehicle number in a society (transaction-scoped advisory lock). */
    private void lockVehicle(UUID societyId, String number) {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) l",
                Integer.class, "parking:" + societyId + ":" + number);
    }

    private void requireNotReservedForOthers(ParkingSlot slot, Instant now, ParkingVerification v) {
        for (ParkingAllocation a : allocations.findOverlappingAny(List.of(slot.getId()), now, now.plusMillis(1))) {
            boolean mine = (v.getVehicleId() != null && v.getVehicleId().equals(a.getVehicleId()))
                    || (v.getRequestId() != null && v.getRequestId().equals(a.getRequestId()))
                    || (a.getVehicleId() == null && v.getFlatId() != null && v.getFlatId().equals(a.getFlatId())
                    && a.getAllocationType() != AllocationType.VISITOR);
            if (!mine) {
                throw new ApiException(ErrorCode.SLOT_RESERVED);
            }
        }
    }

    /** Visitor and hourly-paid allocations end when the vehicle leaves; permanent ones stay. */
    private void releaseTemporary(ParkingSession session) {
        if (session.getRequestId() == null || session.getAllocationId() == null) {
            return;
        }
        allocations.findByIdForUpdate(session.getAllocationId())
                .filter(a -> a.getStatus().live()
                        && (a.getAllocationType() == AllocationType.VISITOR || a.getAllocationType() == AllocationType.PAID)
                        && a.getRequestId() != null)
                .ifPresent(a -> {
                    boolean visitorPass = requests.findById(a.getRequestId()).map(ParkingRequest::isVisitorRequest).orElse(false);
                    if (visitorPass) {
                        allocationService.end(a, AllocationStatus.EXPIRED, "Vehicle exited");
                    }
                });
        if (session.getPermitId() != null) {
            permits.findByIdForUpdate(session.getPermitId())
                    .filter(p -> p.getPermitType() == PermitType.VISITOR && p.getStatus() == PermitStatus.ACTIVE)
                    .ifPresent(p -> allocationService.endPermit(p, PermitStatus.EXPIRED, "Vehicle exited"));
        }
    }

    /** Records the linked visitor's check-in through the visitor module, only when every precondition holds. */
    private void checkInVisitor(ParkingSession session, UserAccess.GuardAccess guard) {
        if (session.getVisitorId() == null || !canOperateVisitors(session.getSocietyId(), guard)) {
            return;
        }
        Visitor visitor = queries.findVisitorById(session.getVisitorId());
        if (visitor == null || visitor.getApprovalStatus() != ApprovalStatus.APPROVED || visitor.getMovement().hasEntered()
                || (visitor.isInvitation() && !visitor.isWithinWindow(clock.instant()))) {
            return;
        }
        visitorService.recordEntry(visitor.getId());
    }

    private void checkOutVisitor(ParkingSession session, UserAccess.GuardAccess guard) {
        if (session.getVisitorId() == null || !canOperateVisitors(session.getSocietyId(), guard)) {
            return;
        }
        Visitor visitor = queries.findVisitorById(session.getVisitorId());
        if (visitor != null && visitor.getMovement().isInside()) {
            visitorService.recordExit(visitor.getId());
        }
    }

    private boolean canOperateVisitors(UUID societyId, UserAccess.GuardAccess guard) {
        return guard.permissions().contains(GuardPermission.VISITOR_ENTRY)
                && access.core().hasPermission(societyId, Permission.GATE_VISITOR_OPERATE);
    }

    private void movement(ParkingSession session, MovementType type, UUID gateId, String actor, String details) {
        ParkingMovement movement = new ParkingMovement();
        movement.setSocietyId(session.getSocietyId());
        movement.setSessionId(session.getId());
        movement.setVehicleNumber(session.getVehicleNumber());
        movement.setEventType(type);
        movement.setGateId(gateId);
        movement.setActorUserId(access.currentUserId());
        movement.setActorName(actor);
        movement.setDetails(details == null ? null : ParkingAllocationService.truncate(details, 300));
        movement.setOccurredAt(clock.instant());
        movements.save(movement);
    }

    private static RateAppliesTo audienceOf(ParkingVerification v) {
        if (v.getRequestId() != null || v.getVisitorId() != null) {
            return RateAppliesTo.VISITOR;
        }
        if (v.getVehicleId() != null && v.getCategory() == Category.RESIDENT) {
            return RateAppliesTo.RESIDENT;
        }
        return RateAppliesTo.PUBLIC;
    }

    private static Category categoryFor(PermitType type, Category vehicleCategory) {
        if (vehicleCategory != null && type != PermitType.VISITOR && type != PermitType.PAID_MONTHLY) {
            return vehicleCategory;
        }
        return ParkingSessionQueryService.categoryOf(type);
    }

    private static String summaryOf(Decision d) {
        return switch (d.decision) {
            case ALLOWED -> "Allowed";
            case ALREADY_INSIDE -> "Already inside";
            case NEEDS_VISITOR_APPROVAL -> "Waiting for visitor approval";
            case NOT_REGISTERED -> "Vehicle not registered";
            case DENIED -> "Entry not allowed";
        };
    }

    private ParkingRefLoader.Refs refsFor() {
        return queries.refs();
    }
}
