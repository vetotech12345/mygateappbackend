package com.mygateapp.deliveries.service;

import com.mygateapp.common.MobileNumbers;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.common.dto.Refs;
import com.mygateapp.common.gate.GateMovementPolicy;
import com.mygateapp.common.gate.MovementStatus;
import com.mygateapp.deliveries.dto.DeliveryDtos;
import com.mygateapp.deliveries.entity.Delivery;
import com.mygateapp.deliveries.entity.DeliveryEvent;
import com.mygateapp.deliveries.repository.DeliveryEventRepository;
import com.mygateapp.deliveries.repository.DeliveryRepository;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
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
import com.mygateapp.society.service.SocietyService;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.repository.UserRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery workflow: guard registers -> (resident approves / rejects / asks to leave it at the gate, when the category
 * needs approval) -> guard records entry -> guard records exit. Entry/exit use the same {@link GateMovementPolicy} as
 * visitors.
 */
@Service
public class DeliveryService {

    public enum Decision { APPROVE, REJECT, LEAVE_AT_GATE }

    private final DeliveryRepository deliveries;
    private final DeliveryEventRepository events;
    private final FlatRepository flats;
    private final GateRepository gates;
    private final GuardRepository guards;
    private final FlatMembershipRepository flatMemberships;
    private final UserRepository users;
    private final SocietyService societyService;
    private final NotificationService notifications;
    private final AccessService access;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DeliveryService(DeliveryRepository deliveries, DeliveryEventRepository events, FlatRepository flats,
                           GateRepository gates, GuardRepository guards, FlatMembershipRepository flatMemberships,
                           UserRepository users, SocietyService societyService, NotificationService notifications,
                           AccessService access, JdbcTemplate jdbc, Clock clock) {
        this.deliveries = deliveries;
        this.events = events;
        this.flats = flats;
        this.gates = gates;
        this.guards = guards;
        this.flatMemberships = flatMemberships;
        this.users = users;
        this.societyService = societyService;
        this.notifications = notifications;
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public DeliveryDtos.DeliveryResponse register(DeliveryDtos.CreateDeliveryRequest request) {
        UserAccess.GuardAccess guard = access.requireOnDutyGuard(GuardPermission.DELIVERY_ENTRY);
        Flat flat = flats.findById(request.flatId())
                .filter(f -> f.getSocietyId().equals(guard.societyId()) && f.isActive())
                .orElseThrow(() -> ApiException.field(ErrorCode.INVALID_FLAT, "flatId", ErrorCode.INVALID_FLAT.defaultMessage()));
        SocietyGate gate = gates.findById(guard.gateId()).orElseThrow(() -> new ApiException(ErrorCode.GUARD_NO_GATE));
        boolean requiresApproval = societyService.settingsFor(guard.societyId()).deliveryRequiresApproval(request.category());
        User actor = currentUser();
        Instant now = clock.instant();

        Delivery delivery = new Delivery();
        delivery.setSocietyId(guard.societyId());
        delivery.setFlat(flat);
        delivery.setGate(gate);
        delivery.setCategory(request.category());
        delivery.setCompany(request.company().trim());
        delivery.setAgentName(request.agentName().trim());
        delivery.setAgentMobile(MobileNumbers.normalizeOptional(request.agentMobile(), "agentMobile"));
        delivery.setPackageCount(request.packageCount() == null ? 1 : request.packageCount());
        delivery.setNotes(Strings.trimToNull(request.notes()));
        delivery.setRequiresApproval(requiresApproval);
        delivery.setApprovalStatus(Delivery.ApprovalStatus.PENDING);
        delivery.setCreatedBy(actor);
        delivery.setRequestedAt(now);
        deliveries.save(delivery);
        record(delivery, "CREATED", "Arrived at " + gate.getName(), null, actor, gate.getId());
        if (!requiresApproval) {
            delivery.setApprovalStatus(Delivery.ApprovalStatus.APPROVED);
            delivery.setDecidedAt(now);
            record(delivery, "AUTO_APPROVED", "Allowed by society rules",
                    request.category().label() + " deliveries do not need resident approval.", null, null);
        }
        String category = request.category().label();
        notifications.notifyUsers(flatMemberships.findActiveResidentUserIds(List.of(flat.getId())), delivery.getSocietyId(),
                NotificationType.DELIVERY_ARRIVAL,
                requiresApproval ? category + " delivery from " + delivery.getCompany() + " needs approval"
                        : category + " delivery from " + delivery.getCompany() + " has arrived",
                delivery.getAgentName() + " is at the " + gate.getName() + ".",
                Notification.ReferenceType.DELIVERY, delivery.getId());
        return toResponse(delivery, true);
    }

    @Transactional
    public DeliveryDtos.DeliveryResponse decide(UUID id, Decision decision) {
        Delivery delivery = lock(id);
        if (!access.isResidentOf(delivery.getFlat().getId())) {
            throw new ApiException(ErrorCode.NOT_FLAT_RESIDENT, "You can only respond to deliveries for your own flat.");
        }
        access.requireResidentFlat(delivery.getFlat().getId(), Permission.DELIVERY_DECIDE);
        if (delivery.getApprovalStatus() != Delivery.ApprovalStatus.PENDING) {
            throw new ApiException(ErrorCode.DELIVERY_ALREADY_DECIDED);
        }
        User actor = currentUser();
        Delivery.ApprovalStatus status = switch (decision) {
            case APPROVE -> Delivery.ApprovalStatus.APPROVED;
            case REJECT -> Delivery.ApprovalStatus.REJECTED;
            case LEAVE_AT_GATE -> Delivery.ApprovalStatus.LEFT_AT_GATE;
        };
        delivery.setApprovalStatus(status);
        delivery.setDecidedBy(actor);
        delivery.setDecidedAt(clock.instant());
        String title = switch (decision) {
            case APPROVE -> "Approved by resident";
            case REJECT -> "Rejected by resident";
            case LEAVE_AT_GATE -> "Resident asked to leave it at the gate";
        };
        record(delivery, decision.name(), title, null, actor, null);
        String body = switch (decision) {
            case APPROVE -> "Approved. The agent can go up.";
            case REJECT -> "Rejected. Do not allow entry.";
            case LEAVE_AT_GATE -> "Keep the parcel at the security desk.";
        };
        List<UUID> atGate = guards.findOnDutyGuardUserIds(delivery.getSocietyId(), delivery.getGate().getId());
        notifications.notifyUsers(atGate.isEmpty() ? guards.findOnDutyGuardUserIds(delivery.getSocietyId(), null) : atGate,
                delivery.getSocietyId(), NotificationType.DELIVERY_ARRIVAL,
                delivery.getCompany() + " delivery for " + delivery.getFlat().toRef().label(), body,
                Notification.ReferenceType.DELIVERY, delivery.getId());
        return toResponse(delivery, true);
    }

    @Transactional
    public DeliveryDtos.DeliveryResponse recordEntry(UUID id) {
        UserAccess.GuardAccess guard = access.requireOnDutyGuard(GuardPermission.DELIVERY_ENTRY);
        Delivery delivery = lockInSociety(id, guard.societyId());
        switch (delivery.getApprovalStatus()) {
            case REJECTED -> throw new ApiException(ErrorCode.DELIVERY_REJECTED);
            case PENDING, LEFT_AT_GATE, CANCELLED -> throw new ApiException(ErrorCode.DELIVERY_NOT_APPROVED);
            case APPROVED -> {
                // movement rules below
            }
        }
        GateMovementPolicy.recordEntry(delivery.getMovement(), clock.instant(), guard.gateId(), access.currentUserId(),
                GateMovementPolicy.DELIVERY);
        record(delivery, "ENTERED", "Agent entered", null, currentUser(), guard.gateId());
        return toResponse(delivery, true);
    }

    @Transactional
    public DeliveryDtos.DeliveryResponse recordExit(UUID id) {
        UserAccess.GuardAccess guard = access.requireOnDutyGuard(GuardPermission.DELIVERY_ENTRY);
        Delivery delivery = lockInSociety(id, guard.societyId());
        GateMovementPolicy.recordExit(delivery.getMovement(), clock.instant(), guard.gateId(), access.currentUserId(),
                GateMovementPolicy.DELIVERY);
        record(delivery, "EXITED", "Agent exited", null, currentUser(), guard.gateId());
        return toResponse(delivery, true);
    }

    // ------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public PageResponse<DeliveryDtos.DeliveryResponse> list(Delivery.Scope scope, String q, Instant from, UUID flatId,
                                                            Integer page, Integer size) {
        UUID societyId = access.currentSocietyId();
        Collection<UUID> restrictToFlats = null;
        if (!access.hasPermission(societyId, Permission.DELIVERY_LOG_VIEW)
                && !access.hasPermission(societyId, Permission.GATE_LOG_VIEW)) {
            if (!access.hasPermission(societyId, Permission.DELIVERY_VIEW_OWN)) {
                throw new ApiException(ErrorCode.ACCESS_DENIED);
            }
            restrictToFlats = access.access().flatIdsIn(societyId);
            if (restrictToFlats.isEmpty()) {
                return PageResponse.of(List.of(), 0, Pages.of(page, size).getPageSize(), 0);
            }
        }
        Delivery.Scope effective = scope == null ? Delivery.Scope.HISTORY : scope;
        Collection<UUID> flatFilter = restrictToFlats;
        Specification<Delivery> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("societyId"), societyId));
            Join<Delivery, Flat> flat = root.join("flat");
            if (flatFilter != null) {
                predicates.add(flat.get("id").in(flatFilter));
            }
            if (flatId != null) {
                predicates.add(cb.equal(flat.get("id"), flatId));
            }
            var approval = root.<Delivery.ApprovalStatus>get("approvalStatus");
            var movement = root.get("movement").<MovementStatus>get("status");
            switch (effective) {
                case PENDING -> predicates.add(cb.equal(approval, Delivery.ApprovalStatus.PENDING));
                case ACTIVE -> {
                    predicates.add(cb.equal(approval, Delivery.ApprovalStatus.APPROVED));
                    predicates.add(movement.in(MovementStatus.NOT_ENTERED, MovementStatus.INSIDE));
                }
                case HISTORY -> {
                }
            }
            String like = Strings.likePattern(q);
            if (like != null) {
                predicates.add(cb.or(cb.like(cb.lower(root.get("company")), like, '\\'),
                        cb.like(cb.lower(root.get("agentName")), like, '\\'),
                        cb.like(cb.lower(flat.get("flatNumber")), like, '\\')));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("requestedAt"), from));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.of(deliveries.findAll(spec, Pages.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt"))),
                d -> toResponse(d, false));
    }

    @Transactional(readOnly = true)
    public DeliveryDtos.DeliveryResponse get(UUID id) {
        Delivery delivery = deliveries.findById(id).orElseThrow(() -> new ApiException(ErrorCode.DELIVERY_NOT_FOUND));
        access.ensureInScope(delivery.getSocietyId(), ErrorCode.DELIVERY_NOT_FOUND);
        boolean allowed = access.hasPermission(delivery.getSocietyId(), Permission.DELIVERY_LOG_VIEW)
                || access.hasPermission(delivery.getSocietyId(), Permission.GATE_LOG_VIEW)
                || access.isResidentOf(delivery.getFlat().getId());
        if (!allowed) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "This delivery is not linked to your flat.");
        }
        return toResponse(delivery, true);
    }

    /** Retention: removes the agent's personal data from deliveries older than the society's retention period. */
    @Transactional
    public int anonymizeExpired(Instant now) {
        return jdbc.update("""
                UPDATE deliveries d
                   SET agent_name = 'Delivery agent', agent_mobile = NULL, notes = NULL, anonymized_at = ?, version = d.version + 1
                  FROM society_settings s
                 WHERE s.society_id = d.society_id AND d.anonymized_at IS NULL AND d.movement_status <> 'INSIDE'
                   AND d.requested_at < ?::timestamptz - make_interval(days => s.visitor_retention_days)
                """, Timestamp.from(now), Timestamp.from(now));
    }

    // ------------------------------------------------------------------ helpers

    private Delivery lock(UUID id) {
        Delivery delivery = deliveries.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.DELIVERY_NOT_FOUND));
        access.ensureInScope(delivery.getSocietyId(), ErrorCode.DELIVERY_NOT_FOUND);
        return delivery;
    }

    private Delivery lockInSociety(UUID id, UUID societyId) {
        Delivery delivery = deliveries.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.DELIVERY_NOT_FOUND));
        if (!delivery.getSocietyId().equals(societyId)) {
            throw new ApiException(ErrorCode.DELIVERY_NOT_FOUND);
        }
        return delivery;
    }

    private void record(Delivery delivery, String type, String title, String description, User actor, UUID gateId) {
        DeliveryEvent event = new DeliveryEvent();
        event.setDeliveryId(delivery.getId());
        event.setSocietyId(delivery.getSocietyId());
        event.setEventType(type);
        event.setStatus(delivery.displayStatus().name());
        event.setTitle(title);
        event.setDescription(description);
        event.setActorUserId(actor == null ? null : actor.getId());
        event.setActorName(actor == null ? "Society rules" : actor.displayName());
        event.setGateId(gateId);
        event.setOccurredAt(clock.instant());
        events.save(event);
    }

    private User currentUser() {
        return users.findById(access.currentUserId()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    private DeliveryDtos.DeliveryResponse toResponse(Delivery d, boolean withHistory) {
        List<Refs.TimelineEvent> history = withHistory
                ? events.findByDeliveryIdOrderByOccurredAtAsc(d.getId()).stream().map(DeliveryEvent::toTimeline).toList()
                : List.of();
        return new DeliveryDtos.DeliveryResponse(d.getId(), d.getCategory(), d.getCompany(), d.getAgentName(),
                d.getAgentMobile(), d.getFlat().toRef(), d.getGate().toRef(), d.displayStatus(), d.getApprovalStatus(),
                d.getMovement().getStatus(), d.getRequestedAt(), d.isRequiresApproval(), d.getDecidedAt(),
                d.getDecidedBy() == null ? null : d.getDecidedBy().displayName(), d.getMovement().getEntryAt(),
                d.getMovement().getExitAt(), d.getPackageCount(), d.getNotes(), history);
    }
}
