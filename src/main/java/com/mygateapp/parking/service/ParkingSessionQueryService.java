package com.mygateapp.parking.service;

import com.mygateapp.common.Pages;
import com.mygateapp.common.TimeRanges;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.common.dto.Refs.FlatRef;
import com.mygateapp.common.dto.Refs.TimelineEvent;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.parking.dto.ParkingDtos.Alert;
import com.mygateapp.parking.dto.ParkingDtos.Charges;
import com.mygateapp.parking.dto.ParkingDtos.GuardParkingDashboard;
import com.mygateapp.parking.dto.ParkingDtos.ParkingSummary;
import com.mygateapp.parking.dto.ParkingDtos.SessionResponse;
import com.mygateapp.parking.dto.ParkingDtos.VehicleSearchResult;
import com.mygateapp.parking.dto.ParkingRefs.Money;
import com.mygateapp.parking.entity.ParkingAllocation;
import com.mygateapp.parking.entity.ParkingMovement;
import com.mygateapp.parking.entity.ParkingPermit;
import com.mygateapp.parking.entity.ParkingRequest;
import com.mygateapp.parking.entity.ParkingSession;
import com.mygateapp.parking.entity.ParkingSlot;
import com.mygateapp.parking.entity.SocietyParkingSettings;
import com.mygateapp.parking.enums.ParkingEnums.AlertSeverity;
import com.mygateapp.parking.enums.ParkingEnums.AlertType;
import com.mygateapp.parking.enums.ParkingEnums.AllocationStatus;
import com.mygateapp.parking.enums.ParkingEnums.ChargeStatus;
import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.enums.ParkingEnums.PermitType;
import com.mygateapp.parking.enums.ParkingEnums.RequestStatus;
import com.mygateapp.parking.enums.ParkingEnums.SearchField;
import com.mygateapp.parking.enums.ParkingEnums.SessionScope;
import com.mygateapp.parking.enums.ParkingEnums.SessionStatus;
import com.mygateapp.parking.repository.ParkingAllocationRepository;
import com.mygateapp.parking.repository.ParkingMovementRepository;
import com.mygateapp.parking.repository.ParkingPermitRepository;
import com.mygateapp.parking.repository.ParkingRequestRepository;
import com.mygateapp.parking.repository.ParkingSessionRepository;
import com.mygateapp.parking.repository.ParkingSlotRepository;
import com.mygateapp.parkingpayment.entity.ParkingPayment;
import com.mygateapp.parkingpayment.repository.PaymentRepository;
import com.mygateapp.parkingpayment.service.ParkingChargeService;
import com.mygateapp.security.Permission;
import com.mygateapp.vehicle.VehicleNumbers;
import com.mygateapp.vehicle.entity.Vehicle;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.RegistrationStatus;
import com.mygateapp.vehicle.repository.VehicleRepository;
import com.mygateapp.visitors.entity.Visitor;
import com.mygateapp.visitors.repository.VisitorRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads of parking sessions plus the guard dashboard, guard vehicle search and the resident parking summary. */
@Service
public class ParkingSessionQueryService {

    private static final int SEARCH_LIMIT = 20;

    /** Slot and permit numbers (normalised like vehicle numbers) can be longer than a vehicle number. */
    private static final java.util.regex.Pattern CODE_SEARCH = java.util.regex.Pattern.compile("^[A-Z0-9]{2,40}$");

    private final ParkingSessionRepository sessions;
    private final ParkingMovementRepository movements;
    private final ParkingPermitRepository permits;
    private final ParkingAllocationRepository allocations;
    private final ParkingRequestRepository requests;
    private final ParkingSlotRepository slots;
    private final VehicleRepository vehicles;
    private final VisitorRepository visitors;
    private final PaymentRepository payments;
    private final ParkingChargeService charges;
    private final ParkingInventoryService inventory;
    private final ParkingSettingsStore settingsStore;
    private final ParkingAccess access;
    private final ParkingSupport support;
    private final ParkingRefLoader refLoader;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ParkingSessionQueryService(ParkingSessionRepository sessions, ParkingMovementRepository movements,
                                      ParkingPermitRepository permits, ParkingAllocationRepository allocations,
                                      ParkingRequestRepository requests, ParkingSlotRepository slots,
                                      VehicleRepository vehicles, VisitorRepository visitors, PaymentRepository payments,
                                      ParkingChargeService charges, ParkingInventoryService inventory,
                                      ParkingSettingsStore settingsStore, ParkingAccess access, ParkingSupport support,
                                      ParkingRefLoader refLoader, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.sessions = sessions;
        this.movements = movements;
        this.permits = permits;
        this.allocations = allocations;
        this.requests = requests;
        this.slots = slots;
        this.vehicles = vehicles;
        this.visitors = visitors;
        this.payments = payments;
        this.charges = charges;
        this.inventory = inventory;
        this.settingsStore = settingsStore;
        this.access = access;
        this.support = support;
        this.refLoader = refLoader;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // =============================================================== sessions

    @Transactional(readOnly = true)
    public PageResponse<SessionResponse> list(SessionScope scope, UUID vehicleId, Category category, String q, Boolean paid,
                                              Instant from, Instant to, Integer page, Integer size) {
        ParkingAccess.Scope viewer = access.readScope(Permission.PARKING_LOG_VIEW, true);
        SessionScope effective = scope == null ? SessionScope.HISTORY : scope;
        if (viewer.resident() && viewer.flatIds().isEmpty()) {
            return PageResponse.of(List.of(), 0, Pages.of(page, size).getPageSize(), 0);
        }
        int grace = settingsStore.settingsFor(viewer.societyId()).getFreeGraceMinutes();
        Instant now = clock.instant();
        String numberLike = q == null || q.isBlank() ? null : "%" + VehicleNumbers.normalize(q).replace("%", "") + "%";
        Specification<ParkingSession> spec = (root, cq, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("societyId"), viewer.societyId()));
            if (viewer.resident()) {
                p.add(root.get("flatId").in(viewer.flatIds()));
            }
            switch (effective) {
                case ACTIVE -> p.add(cb.equal(root.get("status"), SessionStatus.ACTIVE));
                case OVERSTAY -> {
                    p.add(cb.equal(root.get("status"), SessionStatus.ACTIVE));
                    p.add(cb.lessThan(root.get("validUntil"), now.minus(Duration.ofMinutes(grace))));
                }
                case MINE, HISTORY -> {
                }
            }
            if (vehicleId != null) {
                p.add(cb.equal(root.get("vehicleId"), vehicleId));
            }
            if (category != null) {
                p.add(cb.equal(root.get("category"), category));
            }
            if (Boolean.TRUE.equals(paid)) {
                p.add(cb.isNotNull(root.get("rateSnapshot")));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("entryAt"), from));
            }
            if (to != null) {
                p.add(cb.lessThan(root.get("entryAt"), to));
            }
            if (numberLike != null) {
                p.add(cb.like(root.get("vehicleNumber"), numberLike, '\\'));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<ParkingSession> result = sessions.findAll(spec, Pages.of(page, size, Sort.by(Sort.Direction.DESC, "entryAt")));
        Mapper mapper = mapper(viewer.societyId(), result.getContent());
        return PageResponse.of(result, s -> mapper.map(s, false));
    }

    @Transactional(readOnly = true)
    public SessionResponse get(UUID id) {
        ParkingAccess.Scope viewer = access.readScope(Permission.PARKING_LOG_VIEW, true);
        ParkingSession session = sessions.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PARKING_SESSION_NOT_FOUND));
        access.ensureSameSociety(session.getSocietyId(), viewer.societyId(), ErrorCode.PARKING_SESSION_NOT_FOUND);
        if (!viewer.canSeeFlat(session.getFlatId())) {
            throw new ApiException(ErrorCode.PARKING_SESSION_NOT_FOUND);
        }
        return toResponse(session, true);
    }

    public SessionResponse toResponse(ParkingSession session, boolean withHistory) {
        return mapper(session.getSocietyId(), List.of(session)).map(session, withHistory);
    }

    /** Batch mapper for a page of sessions of one society. */
    public Mapper mapper(UUID societyId, Collection<ParkingSession> list) {
        ParkingRefLoader.Refs refs = refLoader.refs()
                .preloadFlats(list.stream().map(ParkingSession::getFlatId).toList())
                .preloadSlots(list.stream().map(ParkingSession::getSlotId).toList())
                .preloadVehicles(list.stream().map(ParkingSession::getVehicleId).toList())
                .preloadPermits(list.stream().map(ParkingSession::getPermitId).toList())
                .preloadGates(list.stream().flatMap(s -> java.util.stream.Stream.of(s.getEntryGateId(), s.getExitGateId())).toList())
                .preloadUsers(list.stream().map(ParkingSession::getEntryRecordedBy).toList());
        Map<UUID, String> visitorNames = new HashMap<>();
        List<UUID> visitorIds = list.stream().map(ParkingSession::getVisitorId).filter(java.util.Objects::nonNull).toList();
        if (!visitorIds.isEmpty()) {
            visitors.findAllById(visitorIds).forEach(v -> visitorNames.put(v.getId(), v.getName()));
        }
        List<UUID> requestIds = list.stream().map(ParkingSession::getRequestId).filter(java.util.Objects::nonNull).toList();
        Map<UUID, String> requestVisitorNames = new HashMap<>();
        if (!requestIds.isEmpty()) {
            requests.findAllById(requestIds).forEach(r -> {
                if (r.getVisitorName() != null) {
                    requestVisitorNames.put(r.getId(), r.getVisitorName());
                }
            });
        }
        return new Mapper(settingsStore.settingsFor(societyId), refs, visitorNames, requestVisitorNames, clock.instant());
    }

    public final class Mapper {

        private final SocietyParkingSettings settings;
        private final ParkingRefLoader.Refs refs;
        private final Map<UUID, String> visitorNames;
        private final Map<UUID, String> requestVisitorNames;
        private final Instant now;

        Mapper(SocietyParkingSettings settings, ParkingRefLoader.Refs refs, Map<UUID, String> visitorNames,
               Map<UUID, String> requestVisitorNames, Instant now) {
            this.settings = settings;
            this.refs = refs;
            this.visitorNames = visitorNames;
            this.requestVisitorNames = requestVisitorNames;
            this.now = now;
        }

        public SessionResponse map(ParkingSession s, boolean withHistory) {
            Charges chargeView;
            boolean exitAllowed = s.isActive();
            String blocked = null;
            if (s.isPaid()) {
                ParkingChargeService.SessionCharge charge = charges.charge(s, now);
                UUID paymentId = payments.findBySessionIdOrderByCreatedAtDesc(s.getId()).stream().findFirst()
                        .map(ParkingPayment::getId).orElse(null);
                String currency = s.getCurrency();
                chargeView = new Charges(charge.rate(), Money.of(charge.charge().total(), currency),
                        Money.of(s.getFinalAmount(), currency), s.getPaymentStatus(), paymentId, s.getPaidUntil(),
                        Money.of(s.getPaidAmount(), currency), Money.of(charge.outstanding(), currency));
                boolean withinPaid = s.getPaidUntil() != null && !now.isAfter(s.getPaidUntil());
                if (s.isActive() && charge.outstanding().signum() > 0 && !withinPaid && settings.isPaymentRequiredBeforeExit()) {
                    exitAllowed = false;
                    blocked = "Collect " + Money.of(charge.outstanding(), currency).amount() + " " + currency + " before exit.";
                }
            } else {
                chargeView = new Charges(null, null, null, ChargeStatus.NOT_APPLICABLE, null, null, null, null);
            }
            String visitorName = s.getVisitorId() != null ? visitorNames.get(s.getVisitorId())
                    : s.getRequestId() != null ? requestVisitorNames.get(s.getRequestId()) : null;
            List<TimelineEvent> history = withHistory
                    ? movements.findBySessionIdOrderByOccurredAtAsc(s.getId()).stream().map(this::timeline).toList()
                    : List.of();
            return new SessionResponse(s.getId(), s.getVehicleNumber(), s.getVehicleType(), s.getCategory(),
                    refs.vehicle(s.getVehicleId()), s.getVisitorId(), visitorName, refs.flat(s.getFlatId()),
                    refs.slot(s.getSlotId()), refs.permit(s.getPermitId()), refs.gate(s.getEntryGateId()), s.getEntryAt(),
                    refs.gate(s.getExitGateId()), s.getExitAt(), s.getValidUntil(),
                    s.displayStatus(now, settings.getFreeGraceMinutes()), chargeView, exitAllowed, blocked,
                    refs.userName(s.getEntryRecordedBy()), s.isEmergencyOverride(), s.getOverrideReason(), s.getRequestId(),
                    history);
        }

        private TimelineEvent timeline(ParkingMovement m) {
            String title = switch (m.getEventType()) {
                case ENTRY -> "Entered";
                case ENTRY_OVERRIDE -> "Entered (emergency override)";
                case EXIT -> "Exited";
                case OVERSTAY -> "Overstay detected";
                case PAYMENT -> "Payment recorded";
                case CANCELLED -> "Session cancelled";
            };
            String gate = m.getGateId() == null ? null : refs.gate(m.getGateId()).name();
            return new TimelineEvent(gate == null ? title : title + " · " + gate, m.getOccurredAt(), m.getActorName(),
                    m.getEventType().name(), m.getDetails());
        }
    }

    // ========================================================= vehicle search

    /** Guard / admin search. No mobile numbers or names are returned for residents; owners are role labels. */
    @Transactional(readOnly = true)
    public List<VehicleSearchResult> search(String q, SearchField by, Category category) {
        UUID societyId = access.societyId();
        if (!access.core().hasPermission(societyId, Permission.VEHICLE_MANAGE)
                && !access.core().hasPermission(societyId, Permission.PARKING_LOG_VIEW)) {
            access.requireVehicleGuard();
        }
        SearchField field = by == null ? SearchField.VEHICLE : by;
        // Every field is compared without spaces, hyphens and dots, so "B1-042" and "B1042" find the same slot and a
        // permit is found by its number as printed.
        String term = field == SearchField.VEHICLE ? VehicleNumbers.requireSearchTerm(q, "q") : codeSearchTerm(q);
        String like = "%" + term + "%";
        PageRequest limit = PageRequest.of(0, SEARCH_LIMIT);
        Instant now = clock.instant();
        Map<String, Vehicle> byNumber = new LinkedHashMap<>();
        Map<String, ParkingPermit> permitByNumber = new HashMap<>();
        Map<String, UUID> slotByNumber = new HashMap<>();
        List<String> extraNumbers = new ArrayList<>();

        switch (field) {
            case VEHICLE -> {
                vehicles.searchByNumber(societyId, like, category, limit).forEach(v -> byNumber.put(v.getRegistrationNumber(), v));
                jdbc.query("""
                        SELECT DISTINCT vehicle_number FROM parking_permits
                        WHERE society_id = :s AND vehicle_number LIKE :q AND status = 'ACTIVE' LIMIT 20
                        """, new MapSqlParameterSource("s", societyId).addValue("q", like), rs -> {
                    extraNumbers.add(rs.getString(1));
                });
                jdbc.query("""
                        SELECT vehicle_number FROM parking_sessions
                        WHERE society_id = :s AND vehicle_number LIKE :q AND status = 'ACTIVE' LIMIT 20
                        """, new MapSqlParameterSource("s", societyId).addValue("q", like), rs -> {
                    extraNumbers.add(rs.getString(1));
                });
            }
            case FLAT -> {
                List<UUID> flatIds = jdbc.queryForList("""
                        SELECT f.id FROM flats f JOIN towers t ON t.id = f.tower_id
                        WHERE f.society_id = :s AND (UPPER(t.code || f.flat_number) LIKE :q OR UPPER(f.flat_number) LIKE :q)
                        LIMIT 10
                        """, new MapSqlParameterSource("s", societyId).addValue("q", like), UUID.class);
                if (!flatIds.isEmpty()) {
                    vehicles.findActiveByFlats(societyId, flatIds, category, limit)
                            .forEach(v -> byNumber.put(v.getRegistrationNumber(), v));
                }
            }
            case SLOT -> {
                List<ParkingSlot> found = slots.searchByNumber(societyId, like, limit);
                List<UUID> slotIds = found.stream().map(ParkingSlot::getId).toList();
                if (!slotIds.isEmpty()) {
                    for (ParkingAllocation a : allocations.findOverlappingAny(slotIds, now, now.plusMillis(1))) {
                        if (a.getVehicleId() != null) {
                            vehicles.findById(a.getVehicleId()).ifPresent(v -> {
                                byNumber.put(v.getRegistrationNumber(), v);
                                slotByNumber.put(v.getRegistrationNumber(), a.getSlotId());
                            });
                        }
                    }
                    for (ParkingSession s : sessions.findActiveInSlots(slotIds)) {
                        extraNumbers.add(s.getVehicleNumber());
                        slotByNumber.put(s.getVehicleNumber(), s.getSlotId());
                    }
                }
            }
            case PERMIT -> {
                for (ParkingPermit p : permits.searchByNumber(societyId, like, limit)) {
                    permitByNumber.putIfAbsent(p.getVehicleNumber(), p);
                    if (p.getVehicleId() != null) {
                        vehicles.findById(p.getVehicleId()).ifPresent(v -> byNumber.put(v.getRegistrationNumber(), v));
                    } else {
                        extraNumbers.add(p.getVehicleNumber());
                    }
                }
            }
        }
        List<String> numbers = new ArrayList<>(byNumber.keySet());
        extraNumbers.stream().filter(n -> !numbers.contains(n)).forEach(numbers::add);
        if (numbers.isEmpty()) {
            return List.of();
        }
        List<String> limited = numbers.subList(0, Math.min(SEARCH_LIMIT, numbers.size()));
        Map<String, ParkingSession> active = sessions.findActiveByNumbers(societyId, limited).stream()
                .collect(Collectors.toMap(ParkingSession::getVehicleNumber, s -> s, (a, b) -> a));
        ParkingRefLoader.Refs refs = refLoader.refs();
        List<VehicleSearchResult> results = new ArrayList<>();
        for (String number : limited) {
            Vehicle v = byNumber.get(number);
            ParkingSession session = active.get(number);
            ParkingPermit permit = permitByNumber.containsKey(number) ? permitByNumber.get(number)
                    : permits.findOpenByNumber(societyId, number).stream()
                    .filter(p -> p.getStatus() == PermitStatus.ACTIVE).findFirst().orElse(null);
            Category resultCategory = v != null ? v.getCategory() : session != null ? session.getCategory()
                    : permit != null ? categoryOf(permit.getPermitType()) : Category.VISITOR;
            if (category != null && resultCategory != category) {
                continue;
            }
            UUID flatId = v != null ? v.getFlatId() : session != null ? session.getFlatId() : permit == null ? null : permit.getFlatId();
            UUID slotId = slotByNumber.getOrDefault(number, session != null && session.getSlotId() != null ? session.getSlotId()
                    : permit == null ? null : permit.getSlotId());
            FlatRef flat = refs.flat(flatId);
            results.add(new VehicleSearchResult(number, v != null ? v.getVehicleType() : session != null
                    ? session.getVehicleType() : permit == null ? null : permit.getVehicleType(), resultCategory,
                    v == null ? null : v.getMake(), v == null ? null : v.getModel(), v == null ? null : v.getColor(),
                    ownerLabel(resultCategory, flat), flat, refs.slot(slotId), ParkingRefLoader.permitRef(permit),
                    v == null ? null : v.getRegistrationStatus(), session != null, session == null ? null : session.getId(),
                    v == null ? null : v.getId()));
        }
        return results;
    }

    static String ownerLabel(Category category, FlatRef flat) {
        return switch (category) {
            case RESIDENT -> flat == null ? "Resident" : "Resident · " + flat.label();
            case VISITOR -> flat == null ? "Visitor" : "Visitor · " + flat.label();
            case PAID -> "Paid parking";
            case SOCIETY -> "Society vehicle";
            case STAFF -> "Staff vehicle";
            case SERVICE -> "Service vehicle";
            case EMERGENCY -> "Emergency vehicle";
        };
    }

    static Category categoryOf(PermitType type) {
        return switch (type) {
            case RESIDENT, TEMPORARY -> Category.RESIDENT;
            case VISITOR -> Category.VISITOR;
            case PAID_MONTHLY -> Category.PAID;
            case SOCIETY -> Category.SOCIETY;
            case STAFF -> Category.STAFF;
            case SERVICE -> Category.SERVICE;
            case EMERGENCY -> Category.EMERGENCY;
        };
    }

    // =========================================================== guard dashboard

    @Transactional(readOnly = true)
    public GuardParkingDashboard guardDashboard() {
        UUID societyId = access.societyId();
        if (!access.core().hasPermission(societyId, Permission.PARKING_LOG_VIEW)) {
            access.requireVehicleGuard();
        }
        Instant now = clock.instant();
        SocietyParkingSettings settings = settingsStore.settingsFor(societyId);
        TimeRanges.Range today = TimeRanges.today(clock, support.zone(societyId));
        List<ParkingSession> recent = sessions.findRecent(societyId, PageRequest.of(0, 10));
        Mapper mapper = mapper(societyId, recent);
        return new GuardParkingDashboard(
                sessions.countBySocietyIdAndStatus(societyId, SessionStatus.ACTIVE),
                sessions.countEntries(societyId, today.from(), today.to()),
                sessions.countExits(societyId, today.from(), today.to()),
                requests.countWaitingVisitorVehicles(societyId, now),
                requests.countBySocietyIdAndStatus(societyId, RequestStatus.PENDING),
                sessions.countActivePaid(societyId),
                sessions.countOverstaying(societyId, now.minus(Duration.ofMinutes(settings.getFreeGraceMinutes()))),
                inventory.areaResponses(societyId, false),
                recent.stream().map(s -> mapper.map(s, false)).toList());
    }

    // ========================================================= resident summary

    @Transactional(readOnly = true)
    public ParkingSummary summary() {
        ParkingAccess.Scope scope = access.residentScope();
        Set<UUID> flatIds = scope.flatIds();
        String currency = settingsStore.settingsFor(scope.societyId()).getCurrency();
        if (flatIds.isEmpty()) {
            return new ParkingSummary(0, 0, 0, 0, 0, 0, 0, Money.of(BigDecimal.ZERO, currency), List.of());
        }
        Instant now = clock.instant();
        SocietyParkingSettings settings = settingsStore.settingsFor(scope.societyId());
        List<Vehicle> flatVehicles = vehicles.findByFlatIdInAndActiveTrue(flatIds);
        List<ParkingAllocation> flatAllocations = allocations.findByFlats(flatIds);
        List<ParkingPermit> flatPermits = permits.findByFlats(flatIds);
        List<ParkingPayment> pending = payments.findPendingForFlats(flatIds);
        Page<ParkingRequest> recentRequests = requests.search(scope.societyId(), true, flatIds, null, null, false,
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "updatedAt")));

        List<Alert> alerts = new ArrayList<>();
        flatPermits.stream()
                .filter(p -> p.getStatus() == PermitStatus.ACTIVE && p.getValidUntil() != null
                        && p.getValidUntil().isAfter(now) && p.getValidUntil().isBefore(now.plus(Duration.ofDays(7)))
                        && p.getPermitType() != PermitType.VISITOR)
                .forEach(p -> alerts.add(new Alert("PERMIT_EXPIRING:" + p.getId(), AlertType.PERMIT_EXPIRING,
                        AlertSeverity.WARNING, "Permit expires soon",
                        p.getPermitNumber() + " for " + p.getVehicleNumber() + " ends soon.", p.getId(), p.getValidUntil())));
        sessions.findOverstayingForFlats(flatIds, now.minus(Duration.ofMinutes(settings.getFreeGraceMinutes())))
                .forEach(s -> alerts.add(new Alert("OVERSTAY:" + s.getId(), AlertType.OVERSTAY, AlertSeverity.CRITICAL,
                        "Vehicle overstaying", s.getVehicleNumber() + " is parked beyond its allowed time.", s.getId(),
                        s.getValidUntil())));
        pending.forEach(p -> alerts.add(new Alert("PAYMENT_DUE:" + p.getId(), AlertType.PAYMENT_DUE, AlertSeverity.WARNING,
                "Payment due", (p.getDescription() == null ? "Parking charges" : p.getDescription()) + " are unpaid.",
                p.getId(), p.getCreatedAt())));
        recentRequests.getContent().stream()
                .filter(r -> r.getDecidedAt() != null && r.getDecidedAt().isAfter(now.minus(Duration.ofDays(3))))
                .forEach(r -> alerts.add(new Alert("REQUEST_UPDATE:" + r.getId(), AlertType.REQUEST_UPDATE,
                        r.getStatus() == RequestStatus.REJECTED ? AlertSeverity.WARNING : AlertSeverity.INFO,
                        "Request " + r.getStatus().name().toLowerCase(), r.getReferenceNumber() + " for "
                        + r.getVehicleNumber() + " was " + r.getStatus().name().toLowerCase() + ".", r.getId(), r.getDecidedAt())));
        flatVehicles.stream()
                .filter(v -> v.getRegistrationStatus() == RegistrationStatus.REJECTED
                        || v.getRegistrationStatus() == RegistrationStatus.SUSPENDED)
                .forEach(v -> alerts.add(new Alert("VEHICLE_UPDATE:" + v.getId(), AlertType.VEHICLE_UPDATE,
                        AlertSeverity.WARNING, "Vehicle " + v.getRegistrationStatus().name().toLowerCase(),
                        v.getRegistrationNumber() + ": " + (v.getReviewRemarks() == null ? "contact the society office."
                                : v.getReviewRemarks()), v.getId(), v.getReviewedAt())));
        alerts.sort(Comparator.comparing(Alert::severity).reversed()
                .thenComparing(a -> a.at() == null ? Instant.EPOCH : a.at(), Comparator.reverseOrder()));

        BigDecimal unpaid = pending.stream().map(ParkingPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ParkingSummary(
                flatVehicles.size(),
                flatVehicles.stream().filter(Vehicle::isApproved).count(),
                flatVehicles.stream().filter(v -> v.getRegistrationStatus() == RegistrationStatus.PENDING).count(),
                flatAllocations.stream().filter(a -> a.getStatus() == AllocationStatus.ACTIVE && a.coversNow(now)).count(),
                recentRequests.getContent().stream().filter(r -> r.getStatus() == RequestStatus.PENDING).count(),
                flatPermits.stream().filter(p -> p.isValidAt(now)).count(),
                sessions.countVisitorVehiclesInside(flatIds),
                Money.of(unpaid, currency),
                alerts);
    }

    public Visitor findVisitorById(UUID id) {
        return id == null ? null : visitors.findById(id).orElse(null);
    }

    public ParkingRefLoader.Refs refs() {
        return refLoader.refs();
    }

    /** Visitor record by vehicle number (normalised comparison; visitor numbers are stored as typed). */
    public Visitor findVisitorByVehicle(UUID societyId, String number, Instant now) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM visitors
                WHERE society_id = :s AND vehicle_number IS NOT NULL
                  AND regexp_replace(UPPER(vehicle_number), '[^A-Z0-9]', '', 'g') = :n
                  AND movement_status <> 'EXITED' AND approval_status IN ('PENDING', 'APPROVED')
                  AND (requested_at > :since OR (valid_until IS NOT NULL AND valid_until > :now))
                ORDER BY requested_at DESC LIMIT 1
                """, new MapSqlParameterSource("s", societyId).addValue("n", number)
                .addValue("since", java.sql.Timestamp.from(now.minus(Duration.ofHours(24))))
                .addValue("now", java.sql.Timestamp.from(now)), UUID.class);
        return ids.isEmpty() ? null : visitors.findById(ids.getFirst()).orElse(null);
    }

    /** Search term for flats, slots and permits: normalised like a vehicle number, up to 40 characters. */
    private static String codeSearchTerm(String raw) {
        String value = VehicleNumbers.normalize(raw);
        if (!CODE_SEARCH.matcher(value).matches()) {
            throw ApiException.field("q", "Enter at least 2 letters or digits.");
        }
        return value;
    }
}
