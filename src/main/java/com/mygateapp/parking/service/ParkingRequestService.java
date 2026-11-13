package com.mygateapp.parking.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.Ids;
import com.mygateapp.common.MobileNumbers;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.parking.dto.ParkingDtos.CreateRequestRequest;
import com.mygateapp.parking.dto.ParkingDtos.CreateVisitorParkingRequest;
import com.mygateapp.parking.dto.ParkingDtos.DecideRequest;
import com.mygateapp.parking.dto.ParkingDtos.RequestResponse;
import com.mygateapp.parking.dto.ParkingDtos.VisitorBlock;
import com.mygateapp.parking.entity.ParkingAllocation;
import com.mygateapp.parking.entity.ParkingPermit;
import com.mygateapp.parking.entity.ParkingRequest;
import com.mygateapp.parking.entity.ParkingSlot;
import com.mygateapp.parking.entity.SocietyParkingSettings;
import com.mygateapp.parking.enums.ParkingEnums.AllocationStatus;
import com.mygateapp.parking.enums.ParkingEnums.AllocationType;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.enums.ParkingEnums.PermitType;
import com.mygateapp.parking.enums.ParkingEnums.RequestStatus;
import com.mygateapp.parking.enums.ParkingEnums.RequestType;
import com.mygateapp.parking.enums.ParkingEnums.VisitorParkingType;
import com.mygateapp.parking.repository.ParkingAllocationRepository;
import com.mygateapp.parking.repository.ParkingPermitRepository;
import com.mygateapp.parking.repository.ParkingRequestRepository;
import com.mygateapp.parkingpayment.dto.PaymentDtos.QuoteResponse;
import com.mygateapp.parkingpayment.dto.PaymentDtos.RateResponse;
import com.mygateapp.parkingpayment.entity.ParkingQuote;
import com.mygateapp.parkingpayment.entity.ParkingRatePlan;
import com.mygateapp.parkingpayment.enums.PaymentEnums.BillingUnit;
import com.mygateapp.parkingpayment.enums.PaymentEnums.RateAppliesTo;
import com.mygateapp.parkingpayment.repository.QuoteRepository;
import com.mygateapp.parkingpayment.service.ParkingChargeService;
import com.mygateapp.parkingpayment.service.ParkingPaymentService;
import com.mygateapp.parkingpayment.service.RatePlanService;
import com.mygateapp.security.Permission;
import com.mygateapp.security.UserAccess;
import com.mygateapp.vehicle.VehicleNumbers;
import com.mygateapp.vehicle.entity.Vehicle;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import com.mygateapp.visitors.entity.Visitor;
import com.mygateapp.visitors.enums.VisitorEnums.ApprovalStatus;
import com.mygateapp.visitors.repository.VisitorRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parking requests of residents (additional slot, temporary permit, paid monthly permit) and visitor parking.
 * Visitor parking only reserves capacity: a linked visitor is still approved through the visitor module, never here.
 */
@Service
public class ParkingRequestService {

    static final Duration MAX_FUTURE_START = Duration.ofDays(30);
    static final Duration MAX_PAID_PERMIT = Duration.ofDays(366);

    private final ParkingRequestRepository requests;
    private final ParkingAllocationRepository allocations;
    private final ParkingPermitRepository permits;
    private final VisitorRepository visitors;
    private final QuoteRepository quotes;
    private final ParkingAllocationService allocationService;
    private final ParkingInventoryService inventory;
    private final RatePlanService ratePlans;
    private final ParkingChargeService charges;
    private final ParkingPaymentService payments;
    private final ParkingSettingsStore settingsStore;
    private final ParkingAccess access;
    private final ParkingSupport support;
    private final ParkingRefLoader refLoader;
    private final AuditService audit;
    private final Clock clock;

    public ParkingRequestService(ParkingRequestRepository requests, ParkingAllocationRepository allocations,
                                 ParkingPermitRepository permits, VisitorRepository visitors, QuoteRepository quotes,
                                 ParkingAllocationService allocationService, ParkingInventoryService inventory,
                                 RatePlanService ratePlans, ParkingChargeService charges, ParkingPaymentService payments,
                                 ParkingSettingsStore settingsStore, ParkingAccess access, ParkingSupport support,
                                 ParkingRefLoader refLoader, AuditService audit, Clock clock) {
        this.requests = requests;
        this.allocations = allocations;
        this.permits = permits;
        this.visitors = visitors;
        this.quotes = quotes;
        this.allocationService = allocationService;
        this.inventory = inventory;
        this.ratePlans = ratePlans;
        this.charges = charges;
        this.payments = payments;
        this.settingsStore = settingsStore;
        this.access = access;
        this.support = support;
        this.refLoader = refLoader;
        this.audit = audit;
        this.clock = clock;
    }

    // ===================================================== resident requests

    @Transactional
    public RequestResponse create(CreateRequestRequest body) {
        if (body.type() == RequestType.VISITOR_SLOT) {
            throw ApiException.field("type", "Use POST /parking/visitor-requests for visitor parking.");
        }
        UserAccess.FlatAccess flat = access.requireResidentFlat(body.flatId(), Permission.PARKING_REQUEST);
        UUID societyId = flat.societyId();
        SocietyParkingSettings settings = settingsStore.settingsFor(societyId);
        Instant now = clock.instant();
        Instant from = body.validFrom() == null ? now : body.validFrom();
        Instant until = body.validUntil();
        ParkingRequest request = new ParkingRequest();
        request.setType(body.type());

        switch (body.type()) {
            case ADDITIONAL_SLOT -> {
                Vehicle vehicle = flatVehicle(body.vehicleId(), flat);
                request.setVehicleId(vehicle.getId());
                request.setVehicleNumber(vehicle.getRegistrationNumber());
                request.setVehicleType(vehicle.getVehicleType());
            }
            case PAID_PERMIT -> {
                if (!settings.isPaidParkingEnabled()) {
                    throw new ApiException(ErrorCode.PARKING_FEATURE_DISABLED, "Paid parking is not enabled in your society.");
                }
                Vehicle vehicle = flatVehicle(body.vehicleId(), flat);
                if (body.rateId() == null) {
                    throw ApiException.field("rateId", "Choose a monthly rate.");
                }
                ratePlans.requireApplicable(body.rateId(), societyId, vehicle.getVehicleType(), RateAppliesTo.RESIDENT,
                        BillingUnit.MONTHLY);
                until = until == null ? from.plus(Duration.ofDays(30)) : until;
                if (Duration.between(from, until).compareTo(MAX_PAID_PERMIT) > 0) {
                    throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "validUntil", "Paid permits can run for at most a year.");
                }
                request.setVehicleId(vehicle.getId());
                request.setVehicleNumber(vehicle.getRegistrationNumber());
                request.setVehicleType(vehicle.getVehicleType());
                request.setRatePlanId(body.rateId());
            }
            case TEMPORARY_PERMIT -> {
                if (body.vehicleId() != null) {
                    Vehicle vehicle = flatVehicle(body.vehicleId(), flat);
                    request.setVehicleId(vehicle.getId());
                    request.setVehicleNumber(vehicle.getRegistrationNumber());
                    request.setVehicleType(vehicle.getVehicleType());
                } else {
                    request.setVehicleNumber(VehicleNumbers.requireLookup(body.vehicleNumber(), "vehicleNumber"));
                    if (body.vehicleType() == null) {
                        throw ApiException.field("vehicleType", "vehicleType is required");
                    }
                    ParkingSettingsStore.requireAllowedType(settings, body.vehicleType(), "vehicleType");
                    request.setVehicleType(body.vehicleType());
                }
                if (body.validUntil() == null) {
                    throw ApiException.field("validUntil", "Choose when the permit ends.");
                }
                if (Duration.between(from, until).compareTo(Duration.ofDays(settings.getMaxTemporaryPermitDays())) > 0) {
                    throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "validUntil",
                            "Temporary permits can be valid for at most " + settings.getMaxTemporaryPermitDays() + " days.");
                }
            }
            default -> throw ApiException.field("type", "Unsupported request type");
        }
        validateWindow(from, until, now);
        if (body.preferredAreaId() != null) {
            inventory.requireArea(body.preferredAreaId(), societyId);
            request.setPreferredAreaId(body.preferredAreaId());
        }
        request.setValidFrom(from);
        request.setValidUntil(until);
        request.setReason(Strings.trimToNull(body.reason()));
        save(societyId, flat.flatId(), request);
        audit.record(societyId, "PARKING_REQUEST_CREATED", "PARKING_REQUEST", request.getId(),
                "Parking request " + request.getReferenceNumber() + " (" + request.getType() + ")",
                Map.of("vehicle", request.getVehicleNumber()));
        support.notifyAdmins(societyId, "New parking request", request.getReferenceNumber() + ": "
                        + label(request.getType()) + " for " + request.getVehicleNumber() + ".",
                Notification.ReferenceType.PARKING_REQUEST, request.getId());
        return toResponse(request, refLoader.refs());
    }

    // ======================================================= visitor parking

    @Transactional
    public RequestResponse createVisitor(CreateVisitorParkingRequest body) {
        UserAccess.FlatAccess flat = access.requireResidentFlat(body.flatId(), Permission.PARKING_REQUEST);
        UUID societyId = flat.societyId();
        SocietyParkingSettings settings = settingsStore.settingsFor(societyId);
        if (!settings.isVisitorParkingEnabled()) {
            throw new ApiException(ErrorCode.PARKING_FEATURE_DISABLED, "Visitor parking is not available in your society.");
        }
        VisitorParkingType parkingType = body.parkingType() == null ? VisitorParkingType.VISITOR : body.parkingType();
        Instant now = clock.instant();
        Instant arrival = body.expectedArrival();
        Instant departure = body.expectedDeparture();
        validateWindow(arrival, departure, now);
        if (Duration.between(arrival, departure).compareTo(Duration.ofHours(settings.getMaxVisitorParkingHours())) > 0) {
            throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "expectedDeparture",
                    "Visitor parking can be booked for at most " + settings.getMaxVisitorParkingHours() + " hours.");
        }
        ParkingSettingsStore.requireAllowedType(settings, body.vehicleType(), "vehicleType");
        String number = VehicleNumbers.requireLookup(body.vehicleNumber(), "vehicleNumber");

        ParkingRequest request = new ParkingRequest();
        request.setType(RequestType.VISITOR_SLOT);
        request.setParkingType(parkingType);
        request.setVehicleNumber(number);
        request.setVehicleType(body.vehicleType());
        request.setValidFrom(arrival);
        request.setValidUntil(departure);
        request.setVisitPurpose(Strings.trimToNull(body.purpose()));
        if (parkingType == VisitorParkingType.PAID) {
            if (!settings.isPaidParkingEnabled()) {
                throw new ApiException(ErrorCode.PARKING_FEATURE_DISABLED, "Paid parking is not enabled in your society.");
            }
            if (body.rateId() == null) {
                throw ApiException.field("rateId", "Choose a rate for paid visitor parking.");
            }
            ParkingRatePlan plan = ratePlans.requireApplicable(body.rateId(), societyId, body.vehicleType(),
                    RateAppliesTo.VISITOR, null);
            if (plan.getBillingUnit() == BillingUnit.MONTHLY) {
                throw new ApiException(ErrorCode.RATE_NOT_APPLICABLE, "Choose an hourly or daily rate.");
            }
            request.setRatePlanId(plan.getId());
        }
        if (body.visitorId() != null) {
            Visitor visitor = visitors.findById(body.visitorId())
                    .filter(v -> v.getSocietyId().equals(societyId) && v.getFlat().getId().equals(flat.flatId()))
                    .orElseThrow(() -> ApiException.field(ErrorCode.VISITOR_NOT_FOUND, "visitorId",
                            "This visitor is not linked to your flat."));
            if (EnumSet.of(ApprovalStatus.REJECTED, ApprovalStatus.CANCELLED, ApprovalStatus.EXPIRED)
                    .contains(visitor.getApprovalStatus()) || visitor.getMovement().getExitAt() != null) {
                throw ApiException.field("visitorId", "This visit is closed. Invite the visitor again.");
            }
            request.setVisitorId(visitor.getId());
            request.setVisitorName(Strings.trimToNull(body.visitorName()) != null ? body.visitorName().trim() : visitor.getName());
            request.setVisitorMobile(body.visitorMobile() != null
                    ? MobileNumbers.normalizeOptional(body.visitorMobile(), "visitorMobile") : visitor.getMobile());
            if (request.getVisitPurpose() == null) {
                request.setVisitPurpose(visitor.getPurpose());
            }
        } else {
            if (Strings.trimToNull(body.visitorName()) == null) {
                throw ApiException.field("visitorName", "Enter the visitor's name.");
            }
            request.setVisitorName(body.visitorName().trim());
            request.setVisitorMobile(MobileNumbers.normalizeOptional(body.visitorMobile(), "visitorMobile"));
        }

        Set<ParkingUsage> usages = visitorUsages(parkingType);
        if (inventory.compute(societyId, usages, body.vehicleType(), arrival, departure, null).free().isEmpty()) {
            throw new ApiException(ErrorCode.PARKING_UNAVAILABLE);
        }
        save(societyId, flat.flatId(), request);
        audit.record(societyId, "VISITOR_PARKING_REQUESTED", "PARKING_REQUEST", request.getId(),
                "Visitor parking " + request.getReferenceNumber() + " for " + number, Map.of("type", parkingType.name()));
        if (settings.isVisitorParkingAutoApprove()) {
            ParkingSlot slot = inventory.findAndLockFreeSlot(societyId, usages, body.vehicleType(), arrival, departure, null)
                    .orElseThrow(() -> new ApiException(ErrorCode.PARKING_UNAVAILABLE));
            approveWith(request, slot, true, null);
        } else {
            support.notifyAdmins(societyId, "Visitor parking request",
                    request.getReferenceNumber() + ": visitor vehicle " + number + " needs a slot.",
                    Notification.ReferenceType.PARKING_REQUEST, request.getId());
        }
        return toResponse(request, refLoader.refs());
    }

    // ============================================================== decisions

    @Transactional
    public RequestResponse approve(UUID id, DecideRequest decision) {
        UUID societyId = access.requireAdmin(Permission.PARKING_ALLOCATION_MANAGE);
        ParkingRequest request = lock(id, societyId);
        if (request.getStatus() != RequestStatus.PENDING) {
            throw new ApiException(ErrorCode.REQUEST_ALREADY_DECIDED);
        }
        ParkingSlot slot = null;
        Set<ParkingUsage> usages = usagesFor(request);
        Instant from = request.getValidFrom() == null ? clock.instant() : request.getValidFrom();
        if (decision != null && decision.slotId() != null) {
            slot = inventory.lockSlot(decision.slotId(), societyId);
            inventory.requireSuitable(slot, usages, request.getVehicleType());
            inventory.requireNoOverlap(slot, from, request.getValidUntil(), null);
        } else if (request.getType() != RequestType.TEMPORARY_PERMIT) {
            slot = inventory.findAndLockFreeSlot(societyId, usages, request.getVehicleType(), from,
                    request.getValidUntil(), request.getPreferredAreaId()).orElse(null);
            if (slot == null && request.getType() != RequestType.PAID_PERMIT) {
                throw new ApiException(ErrorCode.PARKING_UNAVAILABLE);
            }
        }
        approveWith(request, slot, false, decision == null ? null : Strings.trimToNull(decision.remarks()));
        return toResponse(request, refLoader.refs());
    }

    @Transactional
    public RequestResponse reject(UUID id, String remarks) {
        UUID societyId = access.requireAdmin(Permission.PARKING_ALLOCATION_MANAGE);
        ParkingRequest request = lock(id, societyId);
        if (request.getStatus() != RequestStatus.PENDING) {
            throw new ApiException(ErrorCode.REQUEST_ALREADY_DECIDED);
        }
        String reason = Strings.trimToNull(remarks);
        if (reason == null) {
            throw ApiException.field("remarks", "Give a reason for the rejection.");
        }
        request.setStatus(RequestStatus.REJECTED);
        request.setDecidedAt(clock.instant());
        request.setDecidedBy(access.currentUserId());
        request.setDecidedByName(support.displayName(access.currentUserId()));
        request.setDecisionRemarks(reason);
        requests.flush();
        audit.record(societyId, "PARKING_REQUEST_REJECTED", "PARKING_REQUEST", id,
                "Parking request " + request.getReferenceNumber() + " rejected", Map.of("remarks", reason));
        support.notifyFlat(request.getFlatId(), societyId, label(request.getType()) + " rejected",
                request.getReferenceNumber() + " for " + request.getVehicleNumber() + " was not approved.",
                Notification.ReferenceType.PARKING_REQUEST, id);
        return toResponse(request, refLoader.refs());
    }

    /** Residents cancel pending requests, or approved visitor parking that has not been used yet. */
    @Transactional
    public RequestResponse cancel(UUID id) {
        ParkingRequest request = requests.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PARKING_REQUEST_NOT_FOUND));
        access.core().ensureInScope(request.getSocietyId(), ErrorCode.PARKING_REQUEST_NOT_FOUND);
        if (!access.core().isResidentOf(request.getFlatId())) {
            throw new ApiException(ErrorCode.PARKING_REQUEST_NOT_FOUND);
        }
        access.requireResidentFlat(request.getFlatId(), Permission.PARKING_REQUEST);
        boolean unusedVisitorPass = request.getStatus() == RequestStatus.APPROVED && request.isVisitorRequest()
                && request.getConsumedAt() == null;
        if (request.getStatus() != RequestStatus.PENDING && !unusedVisitorPass) {
            throw new ApiException(ErrorCode.REQUEST_NOT_CANCELLABLE);
        }
        request.setStatus(RequestStatus.CANCELLED);
        release(request, AllocationStatus.REVOKED, "Cancelled by the resident");
        requests.flush();
        audit.record(request.getSocietyId(), "PARKING_REQUEST_CANCELLED", "PARKING_REQUEST", id,
                "Parking request " + request.getReferenceNumber() + " cancelled", Map.of());
        return toResponse(request, refLoader.refs());
    }

    // ================================================================== reads

    @Transactional(readOnly = true)
    public PageResponse<RequestResponse> mine(RequestStatus status, RequestType type, Integer page, Integer size) {
        ParkingAccess.Scope scope = access.residentScope();
        return search(scope, status, type, false, page, size);
    }

    /** Admins: all requests of the society. Residents: their own. {@code visitorOnly} for /visitor-requests. */
    @Transactional(readOnly = true)
    public PageResponse<RequestResponse> list(RequestStatus status, RequestType type, boolean visitorOnly, Integer page,
                                              Integer size) {
        UUID societyId = access.societyId();
        ParkingAccess.Scope scope = access.core().hasPermission(societyId, Permission.PARKING_ALLOCATION_MANAGE)
                ? new ParkingAccess.Scope(societyId, ParkingAccess.Viewer.ADMIN, Set.of())
                : access.readScope(Permission.PARKING_LOG_VIEW, false);
        return search(scope, status, type, visitorOnly, page, size);
    }

    @Transactional(readOnly = true)
    public RequestResponse get(UUID id) {
        ParkingAccess.Scope scope = access.readScope(Permission.PARKING_LOG_VIEW, false);
        ParkingRequest request = requests.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PARKING_REQUEST_NOT_FOUND));
        access.ensureSameSociety(request.getSocietyId(), scope.societyId(), ErrorCode.PARKING_REQUEST_NOT_FOUND);
        if (!scope.canSeeFlat(request.getFlatId())) {
            throw new ApiException(ErrorCode.PARKING_REQUEST_NOT_FOUND);
        }
        return toResponse(request, refLoader.refs());
    }

    private PageResponse<RequestResponse> search(ParkingAccess.Scope scope, RequestStatus status, RequestType type,
                                                 boolean visitorOnly, Integer page, Integer size) {
        Page<ParkingRequest> result = requests.search(scope.societyId(), scope.resident(), Ids.orNil(scope.flatIds()),
                status, type, visitorOnly, Pages.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        ParkingRefLoader.Refs refs = refLoader.refs()
                .preloadFlats(result.getContent().stream().map(ParkingRequest::getFlatId).toList())
                .preloadSlots(result.getContent().stream().map(ParkingRequest::getAssignedSlotId).toList())
                .preloadVehicles(result.getContent().stream().map(ParkingRequest::getVehicleId).toList());
        return PageResponse.of(result, r -> toResponse(r, refs));
    }

    // ============================================================== internal

    /** Expiry job: pending requests past their window and unused approved visitor parking. */
    @Transactional
    public boolean expire(UUID id, Instant now) {
        ParkingRequest request = requests.findByIdForUpdate(id).orElse(null);
        if (request == null || request.getValidUntil() == null || request.getValidUntil().isAfter(now)) {
            return false;
        }
        boolean unusedPass = request.getStatus() == RequestStatus.APPROVED && request.isVisitorRequest()
                && request.getConsumedAt() == null;
        if (request.getStatus() != RequestStatus.PENDING && !unusedPass) {
            return false;
        }
        request.setStatus(RequestStatus.EXPIRED);
        release(request, AllocationStatus.EXPIRED, "Not used before the end of the window");
        return true;
    }

    /** Usable approved visitor parking for a number at the gate (window open, or opening within 30 minutes). */
    public Optional<ParkingRequest> usableVisitorRequest(UUID societyId, String number, Instant now) {
        return requests.findUsableVisitorRequests(societyId, number, now, now.plus(Duration.ofMinutes(30))).stream().findFirst();
    }

    private void approveWith(ParkingRequest request, ParkingSlot slot, boolean auto, String remarks) {
        UUID societyId = request.getSocietyId();
        Instant now = clock.instant();
        Instant from = request.getValidFrom() == null ? now : request.getValidFrom();
        Instant until = request.getValidUntil();
        String holder = request.isVisitorRequest() ? request.getVisitorName()
                : refLoader.refs().flat(request.getFlatId()).label();
        com.mygateapp.vehicle.entity.Vehicle vehicle = request.getVehicleId() == null ? null
                : refLoader.refs().vehicleEntity(request.getVehicleId());
        ParkingAllocation allocation = null;
        ParkingPermit permit;
        switch (request.getType()) {
            case ADDITIONAL_SLOT -> {
                if (vehicle == null || !vehicle.isApproved()) {
                    throw new ApiException(ErrorCode.VEHICLE_NOT_APPROVED);
                }
                AllocationType type = until == null ? AllocationType.PERMANENT : AllocationType.TEMPORARY;
                allocation = allocationService.allocate(societyId, slot, type, request.getFlatId(), vehicle.getId(),
                        request.getId(), from, until, AllocationStatus.ACTIVE, request.getReason());
                permit = allocationService.issue(societyId, until == null ? PermitType.RESIDENT : PermitType.TEMPORARY,
                        vehicle, vehicle.getRegistrationNumber(), vehicle.getVehicleType(), request.getFlatId(),
                        slot.getId(), slot.getAreaId(), allocation.getId(), request.getId(), holderOf(vehicle, holder),
                        from, until, List.of(), PermitStatus.ACTIVE);
            }
            case TEMPORARY_PERMIT -> {
                if (slot != null) {
                    allocation = allocationService.allocate(societyId, slot, AllocationType.TEMPORARY, request.getFlatId(),
                            request.getVehicleId(), request.getId(), from, until, AllocationStatus.ACTIVE, request.getReason());
                }
                permit = allocationService.issue(societyId, PermitType.TEMPORARY, vehicle, request.getVehicleNumber(),
                        request.getVehicleType(), request.getFlatId(), slot == null ? null : slot.getId(),
                        slot == null ? request.getPreferredAreaId() : slot.getAreaId(),
                        allocation == null ? null : allocation.getId(), request.getId(), holderOf(vehicle, holder), from,
                        until, List.of(), PermitStatus.ACTIVE);
            }
            case PAID_PERMIT -> {
                ParkingRatePlan plan = ratePlans.require(request.getRatePlanId(), societyId);
                if (slot != null) {
                    allocation = allocationService.allocate(societyId, slot, AllocationType.PAID, request.getFlatId(),
                            request.getVehicleId(), request.getId(), from, until, AllocationStatus.PENDING, request.getReason());
                }
                permit = allocationService.issue(societyId, PermitType.PAID_MONTHLY, vehicle, request.getVehicleNumber(),
                        request.getVehicleType(), request.getFlatId(), slot == null ? null : slot.getId(),
                        slot == null ? request.getPreferredAreaId() : slot.getAreaId(),
                        allocation == null ? null : allocation.getId(), request.getId(), holderOf(vehicle, holder), from,
                        until, List.of("Active after the permit fee is paid"), PermitStatus.PENDING_PAYMENT);
                ParkingQuote quote = charges.permitQuote(societyId, plan, permit.getId(), from, until);
                payments.createPendingForPermit(permit, quote, holderOf(vehicle, holder));
            }
            case VISITOR_SLOT -> {
                AllocationType type = request.getParkingType() == VisitorParkingType.PAID ? AllocationType.PAID
                        : AllocationType.VISITOR;
                allocation = allocationService.allocate(societyId, slot, type, request.getFlatId(), null, request.getId(),
                        from, until, AllocationStatus.ACTIVE, "Visitor parking " + request.getReferenceNumber());
                permit = allocationService.issue(societyId, PermitType.VISITOR, null, request.getVehicleNumber(),
                        request.getVehicleType(), request.getFlatId(), slot.getId(), slot.getAreaId(), allocation.getId(),
                        request.getId(), holder, from, until,
                        request.getParkingType() == VisitorParkingType.PAID ? List.of("Paid parking: charges apply at exit")
                                : List.of(), PermitStatus.ACTIVE);
            }
            default -> throw new IllegalStateException(request.getType().name());
        }
        request.setStatus(RequestStatus.APPROVED);
        request.setAutoApproved(auto);
        request.setDecidedAt(now);
        request.setDecidedBy(auto ? null : access.currentUserId());
        request.setDecidedByName(auto ? "Auto-approved" : support.displayName(access.currentUserId()));
        request.setDecisionRemarks(remarks);
        request.setAssignedSlotId(slot == null ? null : slot.getId());
        request.setAllocationId(allocation == null ? null : allocation.getId());
        request.setPermitId(permit.getId());
        requests.flush();
        audit.record(societyId, auto ? "PARKING_REQUEST_AUTO_APPROVED" : "PARKING_REQUEST_APPROVED", "PARKING_REQUEST",
                request.getId(), "Parking request " + request.getReferenceNumber() + " approved",
                ParkingAllocationService.details("slot", slot == null ? null : slot.getSlotNumber(),
                        "permit", permit.getPermitNumber()));
        support.notifyFlat(request.getFlatId(), societyId, label(request.getType()) + " approved",
                request.getReferenceNumber() + " for " + request.getVehicleNumber() + " is approved"
                        + (slot == null ? "." : ": slot " + slot.getSlotNumber() + "."),
                Notification.ReferenceType.PARKING_REQUEST, request.getId());
    }

    /** Ends the allocation and permit of a cancelled / expired request (releases temporary capacity). */
    private void release(ParkingRequest request, AllocationStatus status, String reason) {
        if (request.getAllocationId() != null) {
            allocations.findByIdForUpdate(request.getAllocationId())
                    .filter(a -> a.getStatus().live())
                    .ifPresent(a -> allocationService.end(a, status, reason));
        }
        if (request.getPermitId() != null) {
            permits.findByIdForUpdate(request.getPermitId())
                    .filter(p -> ParkingAllocationService.OPEN_PERMITS.contains(p.getStatus()))
                    .ifPresent(p -> allocationService.endPermit(p,
                            status == AllocationStatus.EXPIRED ? PermitStatus.EXPIRED : PermitStatus.REVOKED, reason));
        }
    }

    private Set<ParkingUsage> usagesFor(ParkingRequest request) {
        return switch (request.getType()) {
            case ADDITIONAL_SLOT, TEMPORARY_PERMIT -> ParkingUsage.servingCategory(Category.RESIDENT);
            case PAID_PERMIT -> EnumSet.of(ParkingUsage.PAID, ParkingUsage.COMMON, ParkingUsage.RESIDENT);
            case VISITOR_SLOT -> visitorUsages(request.getParkingType());
        };
    }

    private static Set<ParkingUsage> visitorUsages(VisitorParkingType type) {
        return type == VisitorParkingType.PAID ? ParkingUsage.PAID.servedBy() : ParkingUsage.VISITOR.servedBy();
    }

    private Vehicle flatVehicle(UUID vehicleId, UserAccess.FlatAccess flat) {
        if (vehicleId == null) {
            throw ApiException.field("vehicleId", "Choose one of your approved vehicles.");
        }
        Vehicle vehicle = allocationService.requireApprovedVehicle(vehicleId, flat.societyId());
        if (!flat.flatId().equals(vehicle.getFlatId())) {
            throw ApiException.field(ErrorCode.VEHICLE_NOT_FOUND, "vehicleId", "This vehicle is not registered to your flat.");
        }
        return vehicle;
    }

    private void validateWindow(Instant from, Instant until, Instant now) {
        if (until != null && !until.isAfter(from)) {
            throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "validUntil", "The end must be after the start.");
        }
        if (until != null && !until.isAfter(now)) {
            throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "validUntil", "This time window has already ended.");
        }
        if (from.isAfter(now.plus(MAX_FUTURE_START))) {
            throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "validFrom", "Requests can start at most 30 days ahead.");
        }
    }

    private void save(UUID societyId, UUID flatId, ParkingRequest request) {
        request.setSocietyId(societyId);
        request.setFlatId(flatId);
        request.setRequestedBy(access.currentUserId());
        request.setStatus(RequestStatus.PENDING);
        if (requests.existsByFlatIdAndTypeAndVehicleNumberAndStatus(flatId, request.getType(), request.getVehicleNumber(),
                RequestStatus.PENDING)) {
            throw new ApiException(ErrorCode.REQUEST_ALREADY_PENDING);
        }
        request.setReferenceNumber(support.requestNumber(societyId));
        try {
            requests.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.REQUEST_ALREADY_PENDING);
        }
    }

    private ParkingRequest lock(UUID id, UUID societyId) {
        ParkingRequest request = requests.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PARKING_REQUEST_NOT_FOUND));
        access.ensureSameSociety(request.getSocietyId(), societyId, ErrorCode.PARKING_REQUEST_NOT_FOUND);
        return request;
    }

    private static String holderOf(Vehicle vehicle, String fallback) {
        return vehicle != null && vehicle.getOwnerName() != null ? vehicle.getOwnerName() : fallback;
    }

    private static String label(RequestType type) {
        return switch (type) {
            case ADDITIONAL_SLOT -> "Parking slot request";
            case TEMPORARY_PERMIT -> "Temporary permit";
            case VISITOR_SLOT -> "Visitor parking";
            case PAID_PERMIT -> "Paid parking permit";
        };
    }

    // ================================================================ mapping

    public RequestResponse toResponse(ParkingRequest r, ParkingRefLoader.Refs refs) {
        RateResponse rate = r.getRatePlanId() == null ? null : ratePlans.toResponse(ratePlans.require(r.getRatePlanId(), r.getSocietyId()));
        QuoteResponse quote = null;
        if (r.getType() == RequestType.PAID_PERMIT && r.getPermitId() != null) {
            quote = quotes.findFirstByPermitIdOrderByCalculatedAtDesc(r.getPermitId())
                    .map(q -> charges.toResponse(q, rate)).orElse(null);
        }
        VisitorBlock visitor = r.isVisitorRequest()
                ? new VisitorBlock(r.getVisitorId(), r.getVisitorName(), r.getVisitorMobile(), r.getVisitPurpose(),
                r.getValidFrom(), r.getValidUntil())
                : null;
        return new RequestResponse(r.getId(), r.getReferenceNumber(), r.getType(), r.getStatus(), refs.flat(r.getFlatId()),
                refs.vehicle(r.getVehicleId()), r.getVehicleNumber(), r.getVehicleType(), refs.areaRefOf(r.getPreferredAreaId()),
                rate, r.getValidFrom(), r.getValidUntil(), r.getReason(), r.getCreatedAt(), r.getDecidedAt(),
                r.getDecidedByName(), r.getDecisionRemarks(), refs.slot(r.getAssignedSlotId()), refs.permit(r.getPermitId()),
                visitor, quote, refs.userName(r.getRequestedBy()), r.isAutoApproved());
    }
}
