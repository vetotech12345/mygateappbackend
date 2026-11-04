package com.mygateapp.visitors.service;

import com.mygateapp.common.Hashing;
import com.mygateapp.common.MobileNumbers;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.common.gate.GateMovementPolicy;
import com.mygateapp.common.gate.MovementStatus;
import com.mygateapp.common.web.RateLimiter;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.files.entity.StoredFile;
import com.mygateapp.files.service.FileService;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.flats.repository.FlatRepository;
import com.mygateapp.guards.entity.SocietyGate;
import com.mygateapp.guards.repository.GateRepository;
import com.mygateapp.guards.repository.GuardRepository;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.notifications.enums.NotificationType;
import com.mygateapp.notifications.service.NotificationService;
import com.mygateapp.residents.repository.FlatMembershipRepository;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.GuardPermission;
import com.mygateapp.security.Permission;
import com.mygateapp.security.UserAccess;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.entity.SocietySettings;
import com.mygateapp.society.service.SocietyService;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.repository.UserRepository;
import com.mygateapp.visitors.dto.VisitorDtos;
import com.mygateapp.visitors.entity.Visitor;
import com.mygateapp.visitors.entity.VisitorEvent;
import com.mygateapp.visitors.enums.VisitorEnums.ApprovalStatus;
import com.mygateapp.visitors.enums.VisitorEnums.DisplayStatus;
import com.mygateapp.visitors.enums.VisitorEnums.EventType;
import com.mygateapp.visitors.enums.VisitorEnums.Scope;
import com.mygateapp.visitors.enums.VisitorEnums.Source;
import com.mygateapp.visitors.repository.VisitorEventRepository;
import com.mygateapp.visitors.repository.VisitorRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The visitor lifecycle.
 *
 * <pre>
 * Walk-in:     guard registers (PENDING) -> resident approves/rejects -> guard records entry -> guard records exit
 * Invitation:  resident invites (APPROVED + pass code, time window) -> guard verifies code -> entry -> exit
 * </pre>
 *
 * Every state change locks the visitor row, validates the transition, appends an immutable event and notifies the
 * other side. Server time is authoritative; client ids for society/flat/gate are never trusted without checks.
 */
@Service
public class VisitorService {

    static final String MASKED_PASS_CODE = "******";
    private static final DateTimeFormatter WINDOW_FORMAT = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    private final VisitorRepository visitors;
    private final VisitorEventRepository events;
    private final FlatRepository flats;
    private final GateRepository gates;
    private final GuardRepository guards;
    private final FlatMembershipRepository flatMemberships;
    private final UserRepository users;
    private final SocietyService societyService;
    private final FileService files;
    private final NotificationService notifications;
    private final RateLimiter rateLimiter;
    private final AccessService access;
    private final Clock clock;

    public VisitorService(VisitorRepository visitors, VisitorEventRepository events, FlatRepository flats,
                          GateRepository gates, GuardRepository guards, FlatMembershipRepository flatMemberships,
                          UserRepository users, SocietyService societyService, FileService files,
                          NotificationService notifications, RateLimiter rateLimiter, AccessService access, Clock clock) {
        this.visitors = visitors;
        this.events = events;
        this.flats = flats;
        this.gates = gates;
        this.guards = guards;
        this.flatMemberships = flatMemberships;
        this.users = users;
        this.societyService = societyService;
        this.files = files;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.access = access;
        this.clock = clock;
    }

    // ------------------------------------------------------------ guard: walk-in

    @Transactional
    public VisitorDtos.VisitorResponse registerWalkIn(VisitorDtos.CreateVisitorRequest request, MultipartFile photo) {
        UserAccess.GuardAccess guard = access.requireOnDutyGuard(GuardPermission.VISITOR_ENTRY);
        Flat flat = destinationFlat(request.flatId(), guard.societyId());
        SocietyGate gate = gates.findById(guard.gateId()).orElseThrow(() -> new ApiException(ErrorCode.GUARD_NO_GATE));
        User actor = currentUser();
        Instant now = clock.instant();

        Visitor visitor = new Visitor();
        visitor.setSocietyId(guard.societyId());
        visitor.setFlat(flat);
        visitor.setGate(gate);
        visitor.setSource(Source.WALK_IN);
        visitor.setName(request.name().trim());
        visitor.setMobile(MobileNumbers.normalize(MobileNumbers.DEFAULT_COUNTRY_CODE, request.mobile(), "mobile"));
        visitor.setVisitorType(request.type());
        visitor.setPurpose(request.purpose().trim());
        visitor.setVehicleNumber(vehicle(request.vehicleNumber()));
        visitor.setGuestCount(request.guestCount() == null ? 1 : request.guestCount());
        visitor.setNotes(Strings.trimToNull(request.notes()));
        visitor.setApprovalStatus(ApprovalStatus.PENDING);
        visitor.setCreatedBy(actor);
        visitor.setRequestedAt(now);
        if (photo != null && !photo.isEmpty()) {
            StoredFile stored = files.storeImage(photo, StoredFile.Purpose.VISITOR_PHOTO, guard.societyId(), actor.getId());
            visitor.setPhotoFileId(stored.getId());
        }
        visitors.save(visitor);
        record(visitor, EventType.CREATED, "Request created at " + gate.getName(), null, actor, gate.getId());

        notifications.notifyUsers(residentsOf(flat), visitor.getSocietyId(), NotificationType.VISITOR_APPROVAL_REQUEST,
                visitor.getName() + " is at the " + gate.getName(),
                visitor.getVisitorType().label() + " · " + visitor.getPurpose() + ". Tap to approve or reject.",
                Notification.ReferenceType.VISITOR, visitor.getId());
        return toResponse(visitor, true);
    }

    // --------------------------------------------------------- resident: invite

    @Transactional
    public VisitorDtos.VisitorResponse invite(VisitorDtos.InviteVisitorRequest request) {
        UserAccess.FlatAccess flatAccess = access.requireResidentFlat(request.flatId(), Permission.VISITOR_INVITE);
        Flat flat = flats.findById(flatAccess.flatId()).orElseThrow(() -> new ApiException(ErrorCode.FLAT_NOT_FOUND));
        SocietySettings settings = societyService.settingsFor(flat.getSocietyId());
        Instant now = clock.instant();
        validateWindow(request.validFrom(), request.validUntil(), now, settings.getMaxInviteValidityHours());
        User actor = currentUser();

        Visitor visitor = new Visitor();
        visitor.setSocietyId(flat.getSocietyId());
        visitor.setFlat(flat);
        visitor.setSource(Source.INVITATION);
        visitor.setName(request.name().trim());
        visitor.setMobile(MobileNumbers.normalize(MobileNumbers.DEFAULT_COUNTRY_CODE, request.mobile(), "mobile"));
        visitor.setVisitorType(request.type());
        visitor.setPurpose(request.purpose().trim());
        visitor.setVehicleNumber(vehicle(request.vehicleNumber()));
        visitor.setGuestCount(request.guestCount() == null ? 1 : request.guestCount());
        visitor.setApprovalStatus(ApprovalStatus.APPROVED);
        visitor.setCreatedBy(actor);
        visitor.setDecidedBy(actor);
        visitor.setDecidedAt(now);
        visitor.setRequestedAt(now);
        visitor.setValidFrom(request.validFrom());
        visitor.setValidUntil(request.validUntil());
        visitor.setPassCode(newPassCode(flat.getSocietyId()));
        visitors.saveAndFlush(visitor);
        record(visitor, EventType.INVITED, "Invite created", null, actor, null);
        return toResponse(visitor, true);
    }

    // ----------------------------------------------------- resident: decisions

    @Transactional
    public VisitorDtos.VisitorResponse approve(UUID id) {
        return decide(id, true, null);
    }

    @Transactional
    public VisitorDtos.VisitorResponse reject(UUID id, String reason) {
        return decide(id, false, Strings.trimToNull(reason));
    }

    private VisitorDtos.VisitorResponse decide(UUID id, boolean approve, String reason) {
        Visitor visitor = lock(id);
        if (!access.isResidentOf(visitor.getFlat().getId())) {
            throw new ApiException(ErrorCode.NOT_FLAT_RESIDENT, "You can only respond to visitors for your own flat.");
        }
        access.requireResidentFlat(visitor.getFlat().getId(), Permission.VISITOR_DECIDE);
        if (visitor.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new ApiException(ErrorCode.VISITOR_ALREADY_DECIDED);
        }
        User actor = currentUser();
        visitor.setApprovalStatus(approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED);
        visitor.setDecidedBy(actor);
        visitor.setDecidedAt(clock.instant());
        visitor.setRejectionReason(approve ? null : reason);
        record(visitor, approve ? EventType.APPROVED : EventType.REJECTED,
                approve ? "Approved by resident" : "Rejected by resident", reason, actor, null);

        String flatLabel = visitor.getFlat().toRef().label();
        notifications.notifyUsers(guardsFor(visitor), visitor.getSocietyId(),
                approve ? NotificationType.VISITOR_APPROVED : NotificationType.VISITOR_REJECTED,
                visitor.getName() + (approve ? " approved" : " rejected"),
                approve ? flatLabel + " approved entry. You can let them in."
                        : flatLabel + " declined this visitor. Do not allow entry.",
                Notification.ReferenceType.VISITOR, visitor.getId());
        return toResponse(visitor, true);
    }

    // ------------------------------------------------------------------ cancel

    /** Guards cancel pending walk-ins; residents cancel their own upcoming invites. */
    @Transactional
    public VisitorDtos.VisitorResponse cancel(UUID id) {
        Visitor visitor = lock(id);
        User actor = currentUser();
        if (access.isResidentOf(visitor.getFlat().getId())) {
            access.requireResidentFlat(visitor.getFlat().getId(), Permission.VISITOR_INVITE);
            if (!visitor.isInvitation() || visitor.getApprovalStatus() != ApprovalStatus.APPROVED
                    || visitor.getMovement().hasEntered()) {
                throw new ApiException(ErrorCode.VISITOR_NOT_CANCELLABLE,
                        "Only upcoming invites can be cancelled. Reject pending requests instead.");
            }
        } else if (access.access().guardIn(visitor.getSocietyId()).isPresent()) {
            access.requireOnDutyGuard(GuardPermission.VISITOR_ENTRY);
            if (visitor.isInvitation() || visitor.getApprovalStatus() != ApprovalStatus.PENDING) {
                throw new ApiException(ErrorCode.VISITOR_NOT_CANCELLABLE, "Only requests that are still waiting can be cancelled.");
            }
        } else {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        visitor.setApprovalStatus(ApprovalStatus.CANCELLED);
        record(visitor, EventType.CANCELLED, "Cancelled", null, actor, null);
        return toResponse(visitor, true);
    }

    // -------------------------------------------------------- guard: movement

    @Transactional
    public VisitorDtos.VisitorResponse recordEntry(UUID id) {
        UserAccess.GuardAccess guard = access.requireOnDutyGuard(GuardPermission.VISITOR_ENTRY);
        Visitor visitor = lockInSociety(id, guard.societyId());
        Instant now = clock.instant();
        switch (visitor.getApprovalStatus()) {
            case REJECTED -> throw new ApiException(ErrorCode.VISITOR_REJECTED);
            case PENDING -> throw new ApiException(ErrorCode.VISITOR_PENDING_APPROVAL);
            case CANCELLED -> throw new ApiException(visitor.isInvitation() ? ErrorCode.INVITE_CANCELLED : ErrorCode.VISIT_CLOSED);
            case EXPIRED -> throw new ApiException(visitor.isInvitation() ? ErrorCode.INVITE_EXPIRED : ErrorCode.VISIT_CLOSED);
            case APPROVED -> {
                if (visitor.getMovement().getStatus() == MovementStatus.NOT_ENTERED && visitor.isInvitation()) {
                    requireWithinWindow(visitor, now);
                }
            }
        }
        GateMovementPolicy.recordEntry(visitor.getMovement(), now, guard.gateId(), access.currentUserId(), GateMovementPolicy.VISITOR);
        SocietyGate gate = gates.findById(guard.gateId()).orElseThrow(() -> new ApiException(ErrorCode.GUARD_NO_GATE));
        if (visitor.getGate() == null) {
            visitor.setGate(gate);
        }
        record(visitor, EventType.ENTERED, "Entered the premises", null, currentUser(), gate.getId());
        notifications.notifyUsers(residentsOf(visitor.getFlat()), visitor.getSocietyId(), NotificationType.VISITOR_CHECKED_IN,
                visitor.getName() + " has entered", "Checked in at " + gate.getName() + ".",
                Notification.ReferenceType.VISITOR, visitor.getId());
        return toResponse(visitor, true);
    }

    @Transactional
    public VisitorDtos.VisitorResponse recordExit(UUID id) {
        UserAccess.GuardAccess guard = access.requireOnDutyGuard(GuardPermission.VISITOR_ENTRY);
        Visitor visitor = lockInSociety(id, guard.societyId());
        GateMovementPolicy.recordExit(visitor.getMovement(), clock.instant(), guard.gateId(), access.currentUserId(),
                GateMovementPolicy.VISITOR);
        record(visitor, EventType.EXITED, "Exited the premises", null, currentUser(), guard.gateId());
        return toResponse(visitor, true);
    }

    /** Guard looks up an invitation by its 6-digit code. Limited to 30 lookups per minute per guard. */
    @Transactional(readOnly = true)
    public VisitorDtos.VisitorResponse verifyPass(String passCode) {
        UserAccess.GuardAccess guard = access.requireOnDutyGuard(GuardPermission.VISITOR_ENTRY);
        if (!rateLimiter.tryAcquire("pass-verify:" + access.currentUserId(), 30, Duration.ofMinutes(1))) {
            throw new ApiException(ErrorCode.RATE_LIMITED);
        }
        List<Visitor> candidates = visitors.findInvitationsByPassCode(guard.societyId(), passCode.trim());
        if (candidates.isEmpty()) {
            throw new ApiException(ErrorCode.INVITE_NOT_FOUND);
        }
        Instant now = clock.instant();
        Visitor usable = candidates.stream()
                .filter(v -> v.getApprovalStatus() == ApprovalStatus.APPROVED && !v.getMovement().hasEntered())
                .findFirst().orElse(null);
        if (usable != null) {
            requireWithinWindow(usable, now);
            return toResponse(usable, true, true);
        }
        Visitor latest = candidates.getFirst();
        if (latest.getMovement().hasEntered()) {
            throw new ApiException(ErrorCode.INVITE_ALREADY_USED);
        }
        throw new ApiException(latest.getApprovalStatus() == ApprovalStatus.CANCELLED
                ? ErrorCode.INVITE_CANCELLED : ErrorCode.INVITE_EXPIRED);
    }

    // ------------------------------------------------------------------- reads

    public record ListQuery(Scope scope, DisplayStatus status, String search, Instant from, UUID flatId,
                            Source source, Integer page, Integer size) {
    }

    @Transactional(readOnly = true)
    public PageResponse<VisitorDtos.VisitorResponse> list(ListQuery query) {
        UUID societyId = access.currentSocietyId();
        Collection<UUID> restrictToFlats = null;
        if (!access.hasPermission(societyId, Permission.VISITOR_LOG_VIEW)
                && !access.hasPermission(societyId, Permission.GATE_LOG_VIEW)) {
            if (!access.hasPermission(societyId, Permission.VISITOR_VIEW_OWN)) {
                throw new ApiException(ErrorCode.ACCESS_DENIED);
            }
            restrictToFlats = access.access().flatIdsIn(societyId);
            if (restrictToFlats.isEmpty()) {
                return PageResponse.of(List.of(), 0, Pages.of(query.page(), query.size()).getPageSize(), 0);
            }
        }
        Scope scope = query.scope() == null ? Scope.HISTORY : query.scope();
        Sort sort = switch (scope) {
            case INSIDE -> Sort.by(Sort.Direction.DESC, "movement.entryAt");
            case HISTORY -> Sort.by(Sort.Direction.DESC, "updatedAt");
            default -> Sort.by(Sort.Direction.DESC, "requestedAt");
        };
        Specification<Visitor> spec = specification(societyId, restrictToFlats, scope, query, clock.instant());
        Page<Visitor> page = visitors.findAll(spec, Pages.of(query.page(), query.size(), sort));
        return PageResponse.of(page, v -> toResponse(v, false));
    }

    @Transactional(readOnly = true)
    public VisitorDtos.VisitorResponse get(UUID id) {
        Visitor visitor = visitors.findById(id).orElseThrow(() -> new ApiException(ErrorCode.VISITOR_NOT_FOUND));
        access.ensureInScope(visitor.getSocietyId(), ErrorCode.VISITOR_NOT_FOUND);
        boolean allowed = access.hasPermission(visitor.getSocietyId(), Permission.VISITOR_LOG_VIEW)
                || access.hasPermission(visitor.getSocietyId(), Permission.GATE_LOG_VIEW)
                || access.isResidentOf(visitor.getFlat().getId());
        if (!allowed) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "This visitor is not linked to your flat.");
        }
        return toResponse(visitor, true);
    }

    // ------------------------------------------------------------ maintenance

    /** Expires one invitation whose window has passed (called by the maintenance job). */
    @Transactional
    public boolean expireInvitation(UUID id, Instant now) {
        Visitor visitor = visitors.findByIdForUpdate(id).orElse(null);
        if (visitor == null || !visitor.isInvitation() || visitor.getApprovalStatus() != ApprovalStatus.APPROVED
                || visitor.getMovement().hasEntered() || visitor.getValidUntil() == null || !now.isAfter(visitor.getValidUntil())) {
            return false;
        }
        visitor.setApprovalStatus(ApprovalStatus.EXPIRED);
        record(visitor, EventType.EXPIRED, "Invite expired", null, null, null);
        return true;
    }

    /** Expires a walk-in request nobody answered (society setting pendingVisitorExpiryMinutes). */
    @Transactional
    public boolean expirePending(UUID id) {
        Visitor visitor = visitors.findByIdForUpdate(id).orElse(null);
        if (visitor == null || visitor.getApprovalStatus() != ApprovalStatus.PENDING) {
            return false;
        }
        visitor.setApprovalStatus(ApprovalStatus.EXPIRED);
        record(visitor, EventType.EXPIRED, "Request expired without a response", null, null, null);
        return true;
    }

    /** Retention: removes personal data from an old visit; the event history (which has no names) is kept. */
    @Transactional
    public void anonymize(UUID id) {
        Visitor visitor = visitors.findByIdForUpdate(id).orElse(null);
        if (visitor == null || visitor.getAnonymizedAt() != null) {
            return;
        }
        UUID photo = visitor.getPhotoFileId();
        visitor.setName("Visitor (redacted)");
        visitor.setMobile(null);
        visitor.setVehicleNumber(null);
        visitor.setNotes(null);
        visitor.setRejectionReason(null);
        visitor.setPhotoFileId(null);
        visitor.setAnonymizedAt(clock.instant());
        visitors.flush();
        if (photo != null) {
            files.delete(photo);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Visitor lock(UUID id) {
        Visitor visitor = visitors.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.VISITOR_NOT_FOUND));
        access.ensureInScope(visitor.getSocietyId(), ErrorCode.VISITOR_NOT_FOUND);
        return visitor;
    }

    private Visitor lockInSociety(UUID id, UUID societyId) {
        Visitor visitor = visitors.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.VISITOR_NOT_FOUND));
        if (!visitor.getSocietyId().equals(societyId)) {
            throw new ApiException(ErrorCode.VISITOR_NOT_FOUND);
        }
        return visitor;
    }

    private Flat destinationFlat(UUID flatId, UUID societyId) {
        return flats.findById(flatId)
                .filter(f -> f.getSocietyId().equals(societyId) && f.isActive())
                .orElseThrow(() -> ApiException.field(ErrorCode.INVALID_FLAT, "flatId", ErrorCode.INVALID_FLAT.defaultMessage()));
    }

    private void validateWindow(Instant from, Instant until, Instant now, int maxHours) {
        if (!until.isAfter(from)) {
            throw ApiException.field(ErrorCode.INVALID_INVITE_WINDOW, "validUntil", "The end time must be after the start time.");
        }
        if (!until.isAfter(now)) {
            throw ApiException.field(ErrorCode.INVALID_INVITE_WINDOW, "validUntil", "The invite window has already ended.");
        }
        if (Duration.between(from, until).compareTo(Duration.ofHours(maxHours)) > 0) {
            throw ApiException.field(ErrorCode.INVALID_INVITE_WINDOW, "validUntil",
                    "Invites can be valid for at most " + maxHours + " hours.");
        }
        if (from.isAfter(now.plus(Duration.ofDays(30)))) {
            throw ApiException.field(ErrorCode.INVALID_INVITE_WINDOW, "validFrom", "Invites can start at most 30 days ahead.");
        }
    }

    private void requireWithinWindow(Visitor visitor, Instant now) {
        if (visitor.isWithinWindow(now)) {
            return;
        }
        if (now.isAfter(visitor.getValidUntil())) {
            throw new ApiException(ErrorCode.INVITE_EXPIRED);
        }
        Society society = societyService.requireSociety(visitor.getSocietyId());
        throw new ApiException(ErrorCode.INVITE_NOT_VALID_NOW,
                "This invite is valid from " + WINDOW_FORMAT.withZone(society.zoneId()).format(visitor.getValidFrom()) + ".");
    }

    private String newPassCode(UUID societyId) {
        for (int attempt = 0; attempt < 25; attempt++) {
            String code = String.valueOf(100000 + Hashing.randomInt(900000));
            if (!visitors.activePassCodeExists(societyId, code)) {
                return code;
            }
        }
        throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Could not generate an invite code. Please try again.");
    }

    private void record(Visitor visitor, EventType type, String title, String description, User actor, UUID gateId) {
        VisitorEvent event = new VisitorEvent();
        event.setVisitorId(visitor.getId());
        event.setSocietyId(visitor.getSocietyId());
        event.setEventType(type.name());
        event.setStatus(visitor.displayStatus().name());
        event.setTitle(title);
        event.setDescription(description);
        event.setActorUserId(actor == null ? null : actor.getId());
        event.setActorName(actor == null ? "System" : actor.displayName());
        event.setGateId(gateId);
        event.setOccurredAt(clock.instant());
        events.save(event);
    }

    private Collection<UUID> residentsOf(Flat flat) {
        return flatMemberships.findActiveResidentUserIds(List.of(flat.getId()));
    }

    /** Guards on duty at the visitor's gate, or any on-duty guard of the society. */
    private Collection<UUID> guardsFor(Visitor visitor) {
        UUID gateId = visitor.getGate() == null ? null : visitor.getGate().getId();
        List<UUID> atGate = gateId == null ? List.of() : guards.findOnDutyGuardUserIds(visitor.getSocietyId(), gateId);
        return atGate.isEmpty() ? guards.findOnDutyGuardUserIds(visitor.getSocietyId(), null) : atGate;
    }

    private User currentUser() {
        return users.findById(access.currentUserId()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    private static String vehicle(String value) {
        String trimmed = Strings.trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase();
    }

    private Specification<Visitor> specification(UUID societyId, Collection<UUID> flatIds, Scope scope, ListQuery query,
                                                 Instant now) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("societyId"), societyId));
            Join<Visitor, Flat> flat = root.join("flat");
            if (flatIds != null) {
                predicates.add(flat.get("id").in(flatIds));
            }
            if (query.flatId() != null) {
                predicates.add(cb.equal(flat.get("id"), query.flatId()));
            }
            if (query.source() != null) {
                predicates.add(cb.equal(root.get("source"), query.source()));
            }
            var approval = root.<ApprovalStatus>get("approvalStatus");
            var movement = root.get("movement").<MovementStatus>get("status");
            switch (scope) {
                case PENDING -> predicates.add(cb.equal(approval, ApprovalStatus.PENDING));
                case EXPECTED -> {
                    predicates.add(cb.equal(approval, ApprovalStatus.APPROVED));
                    predicates.add(cb.equal(movement, MovementStatus.NOT_ENTERED));
                    predicates.add(cb.or(cb.equal(root.get("source"), Source.WALK_IN),
                            cb.greaterThanOrEqualTo(root.get("validUntil"), now)));
                }
                case INSIDE -> predicates.add(cb.equal(movement, MovementStatus.INSIDE));
                case HISTORY -> {
                }
            }
            if (query.status() != null) {
                predicates.add(switch (query.status()) {
                    case CHECKED_IN -> cb.equal(movement, MovementStatus.INSIDE);
                    case CHECKED_OUT -> cb.equal(movement, MovementStatus.EXITED);
                    case APPROVED -> cb.and(cb.equal(approval, ApprovalStatus.APPROVED),
                            cb.equal(movement, MovementStatus.NOT_ENTERED), cb.equal(root.get("source"), Source.WALK_IN));
                    case PRE_APPROVED -> cb.and(cb.equal(approval, ApprovalStatus.APPROVED),
                            cb.equal(movement, MovementStatus.NOT_ENTERED), cb.equal(root.get("source"), Source.INVITATION));
                    default -> cb.equal(approval, ApprovalStatus.valueOf(query.status().name()));
                });
            }
            String like = Strings.likePattern(query.search());
            if (like != null) {
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like, '\\'),
                        cb.like(root.get("mobile"), like, '\\'),
                        cb.like(cb.lower(root.get("purpose")), like, '\\'),
                        cb.like(cb.lower(root.get("vehicleNumber")), like, '\\'),
                        cb.like(cb.lower(flat.get("flatNumber")), like, '\\')));
            }
            if (query.from() != null) {
                predicates.add(cb.or(cb.greaterThanOrEqualTo(root.get("requestedAt"), query.from()),
                        cb.greaterThanOrEqualTo(root.get("movement").get("entryAt"), query.from()),
                        cb.greaterThanOrEqualTo(root.get("movement").get("exitAt"), query.from())));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    // ----------------------------------------------------------------- mapping

    private VisitorDtos.VisitorResponse toResponse(Visitor v, boolean withHistory) {
        return toResponse(v, withHistory, false);
    }

    /**
     * Pass codes are shown to the flat's residents and admins. Guards see a masked value (so the app still knows it is
     * an invite) except in the verify-pass response, where the guard has just typed the code.
     */
    private VisitorDtos.VisitorResponse toResponse(Visitor v, boolean withHistory, boolean revealPassCode) {
        String passCode = v.getPassCode();
        if (passCode != null && !revealPassCode && !access.isResidentOf(v.getFlat().getId())
                && !access.hasPermission(v.getSocietyId(), Permission.VISITOR_LOG_VIEW)) {
            passCode = MASKED_PASS_CODE;
        }
        List<com.mygateapp.common.dto.Refs.TimelineEvent> history = withHistory
                ? events.findByVisitorIdOrderByOccurredAtAsc(v.getId()).stream().map(VisitorEvent::toTimeline).toList()
                : List.of();
        return new VisitorDtos.VisitorResponse(
                v.getId(), v.getName(), v.getMobile(), v.getVisitorType(), files.signedUrl(v.getPhotoFileId()),
                v.getFlat().toRef(), v.getGate() == null ? null : v.getGate().toRef(), v.getPurpose(), v.displayStatus(),
                v.getApprovalStatus(), v.getMovement().getStatus(), v.getSource(), v.getRequestedAt(), v.getDecidedAt(),
                v.getDecidedBy() == null ? null : v.getDecidedBy().displayName(), v.getRejectionReason(),
                v.getMovement().getEntryAt(), v.getMovement().getExitAt(), v.getVehicleNumber(),
                v.getCreatedBy().displayName(), passCode, v.getValidFrom(), v.getValidUntil(), v.getGuestCount(),
                v.getNotes(), history);
    }
}
