package com.mygateapp.guards.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.common.dto.StatusRequest;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.guards.dto.GuardDtos;
import com.mygateapp.guards.entity.GuardAssignment;
import com.mygateapp.guards.entity.GuardDutyLog;
import com.mygateapp.guards.entity.SecurityGuard;
import com.mygateapp.guards.entity.SocietyGate;
import com.mygateapp.guards.repository.GuardAssignmentRepository;
import com.mygateapp.guards.repository.GuardDutyLogRepository;
import com.mygateapp.guards.repository.GuardRepository;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.GuardPermission;
import com.mygateapp.security.Permission;
import com.mygateapp.security.Role;
import com.mygateapp.security.UserAccess;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.society.repository.SocietyMembershipRepository;
import com.mygateapp.users.dto.UserMeResponse;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.service.UserMeService;
import com.mygateapp.users.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Security guards: registration, gate/shift assignment, status, duty toggle and activity log. */
@Service
public class GuardService {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "employeeCode", "status");

    private final GuardRepository guards;
    private final GuardAssignmentRepository assignments;
    private final GuardDutyLogRepository dutyLogs;
    private final SocietyMembershipRepository memberships;
    private final GateService gateService;
    private final UserService userService;
    private final UserMeService userMeService;
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public GuardService(GuardRepository guards, GuardAssignmentRepository assignments, GuardDutyLogRepository dutyLogs,
                        SocietyMembershipRepository memberships, GateService gateService, UserService userService,
                        UserMeService userMeService, NamedParameterJdbcTemplate jdbc, AccessService access,
                        AuditService audit, Clock clock) {
        this.guards = guards;
        this.assignments = assignments;
        this.dutyLogs = dutyLogs;
        this.memberships = memberships;
        this.gateService = gateService;
        this.userService = userService;
        this.userMeService = userMeService;
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public GuardDtos.GuardResponse create(GuardDtos.CreateGuardRequest request) {
        UUID societyId = access.resolveSocietyId(request.societyId());
        access.require(societyId, Permission.GUARD_MANAGE);
        User user = userService.findOrCreate(request.countryCode(), request.mobile(), request.name(), null);
        if (guards.findBySocietyIdAndUserId(societyId, user.getId()).isPresent()) {
            throw new ApiException(ErrorCode.GUARD_ALREADY_EXISTS);
        }
        SecurityGuard guard = new SecurityGuard();
        guard.setSocietyId(societyId);
        guard.setUser(user);
        guard.setEmployeeCode(Strings.trimToNull(request.employeeCode()));
        guard.setAgencyName(Strings.trimToNull(request.agencyName()));
        guard.setPermissions(request.permissions() == null || request.permissions().isEmpty()
                ? EnumSet.allOf(GuardPermission.class) : EnumSet.copyOf(request.permissions()));
        guards.save(guard);
        setMembership(societyId, user.getId(), SocietyMembership.Status.ACTIVE);
        GuardAssignment assignment = null;
        if (request.gateId() != null) {
            assignment = assign(guard, request.gateId(), request.shiftName(), request.shiftStart(), request.shiftEnd());
        }
        audit.record(societyId, "GUARD_CREATED", "GUARD", guard.getId(), user.displayName() + " registered as guard",
                Map.of("permissions", guard.getPermissions().toString()));
        return toResponse(guard, assignment);
    }

    @Transactional(readOnly = true)
    public PageResponse<GuardDtos.GuardResponse> list(UUID societyIdParam, ActiveStatus status, String q, Integer page,
                                                      Integer size, String sort) {
        UUID societyId = access.resolveSocietyId(societyIdParam);
        access.require(societyId, Permission.GUARD_MANAGE);
        var pageable = Pages.of(page, size, sort, SORT_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = guards.search(societyId, status, Strings.likePattern(q), pageable);
        Map<UUID, GuardAssignment> active = assignments.findByGuardIdInAndActiveTrue(
                        result.getContent().stream().map(SecurityGuard::getId).toList()).stream()
                .collect(Collectors.toMap(GuardAssignment::getGuardId, Function.identity()));
        return PageResponse.of(result, g -> toResponse(g, active.get(g.getId())));
    }

    @Transactional(readOnly = true)
    public GuardDtos.GuardResponse get(UUID id) {
        SecurityGuard guard = requireGuardEntity(id);
        boolean self = guard.getUser().getId().equals(access.currentUserId());
        if (!self) {
            access.require(guard.getSocietyId(), Permission.GUARD_MANAGE);
        }
        return toResponse(guard, assignments.findByGuardIdAndActiveTrue(id).orElse(null));
    }

    @Transactional
    public GuardDtos.GuardResponse update(UUID id, GuardDtos.UpdateGuardRequest request) {
        SecurityGuard guard = requireGuardEntity(id);
        access.require(guard.getSocietyId(), Permission.GUARD_MANAGE);
        if (request.name() != null) {
            guard.getUser().setName(request.name().trim());
        }
        if (request.employeeCode() != null) {
            guard.setEmployeeCode(Strings.trimToNull(request.employeeCode()));
        }
        if (request.agencyName() != null) {
            guard.setAgencyName(Strings.trimToNull(request.agencyName()));
        }
        if (request.permissions() != null) {
            guard.getPermissions().clear();
            guard.getPermissions().addAll(request.permissions());
        }
        audit.record(guard.getSocietyId(), "GUARD_UPDATED", "GUARD", id, "Guard " + guard.getUser().displayName() + " updated",
                Map.of("permissions", guard.getPermissions().toString()));
        return toResponse(guard, assignments.findByGuardIdAndActiveTrue(id).orElse(null));
    }

    @Transactional
    public GuardDtos.GuardResponse updateStatus(UUID id, StatusRequest request) {
        SecurityGuard guard = requireGuardEntity(id);
        access.require(guard.getSocietyId(), Permission.GUARD_MANAGE);
        ActiveStatus previous = guard.getStatus();
        guard.setStatus(request.status());
        if (request.status() == ActiveStatus.INACTIVE && guard.isOnDuty()) {
            endDuty(guard, clock.instant());
        }
        setMembership(guard.getSocietyId(), guard.getUser().getId(), request.status() == ActiveStatus.ACTIVE
                ? SocietyMembership.Status.ACTIVE : SocietyMembership.Status.INACTIVE);
        audit.record(guard.getSocietyId(), "GUARD_STATUS_CHANGED", "GUARD", id,
                "Guard " + guard.getUser().displayName() + " " + previous + " -> " + request.status(),
                Map.of("reason", request.reason() == null ? "" : request.reason()));
        return toResponse(guard, assignments.findByGuardIdAndActiveTrue(id).orElse(null));
    }

    @Transactional
    public GuardDtos.GuardResponse assignGate(UUID id, GuardDtos.AssignGateRequest request) {
        SecurityGuard guard = requireGuardEntity(id);
        access.require(guard.getSocietyId(), Permission.GUARD_MANAGE);
        GuardAssignment assignment = assign(guard, request.gateId(), request.shiftName(), request.shiftStart(), request.shiftEnd());
        audit.record(guard.getSocietyId(), "GUARD_ASSIGNED", "GUARD", id,
                guard.getUser().displayName() + " assigned to " + assignment.getGate().getName(),
                Map.of("gateId", assignment.getGate().getId(), "shift", assignment.shiftLabel()));
        return toResponse(guard, assignment);
    }

    @Transactional(readOnly = true)
    public List<GuardDtos.AssignmentView> assignmentHistory(UUID id) {
        SecurityGuard guard = requireGuardEntity(id);
        access.require(guard.getSocietyId(), Permission.GUARD_MANAGE);
        return assignments.findByGuardIdOrderByValidFromDesc(id).stream().map(GuardDtos.AssignmentView::of).toList();
    }

    /** {@code POST /guards/me/duty}: the Flutter duty toggle. Returns the refreshed {@code /users/me}. */
    @Transactional
    public UserMeResponse setDuty(boolean onDuty) {
        UUID societyId = access.currentSocietyId();
        UserAccess.GuardAccess current = access.requireGuard(societyId);
        SecurityGuard guard = guards.findById(current.guardId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_A_GUARD));
        Instant now = clock.instant();
        if (onDuty && !guard.isOnDuty()) {
            guard.setOnDuty(true);
            guard.setDutyChangedAt(now);
            if (dutyLogs.findByGuardIdAndEndedAtIsNull(guard.getId()).isEmpty()) {
                GuardDutyLog log = new GuardDutyLog();
                log.setSocietyId(societyId);
                log.setGuardId(guard.getId());
                log.setGateId(current.gateId());
                log.setStartedAt(now);
                dutyLogs.save(log);
            }
        } else if (!onDuty && guard.isOnDuty()) {
            endDuty(guard, now);
        }
        guards.flush();
        access.invalidate();
        return userMeService.build(guard.getUser().getId());
    }

    /** Admin view of everything a guard recorded: visitor and delivery events, staff attendance and duty periods. */
    @Transactional(readOnly = true)
    public PageResponse<GuardDtos.GuardActivityItem> activity(UUID id, Integer page, Integer size) {
        SecurityGuard guard = requireGuardEntity(id);
        access.require(guard.getSocietyId(), Permission.GUARD_MANAGE);
        var pageable = Pages.of(page, size);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", guard.getUser().getId())
                .addValue("guardId", guard.getId())
                .addValue("societyId", guard.getSocietyId())
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        String union = """
                SELECT e.occurred_at AS at, 'VISITOR' AS kind, v.name || ': ' || e.title AS title, e.visitor_id AS ref, e.status AS status
                  FROM visitor_events e JOIN visitors v ON v.id = e.visitor_id
                 WHERE e.actor_user_id = :userId AND e.society_id = :societyId
                UNION ALL
                SELECT e.occurred_at, 'DELIVERY', d.company || ': ' || e.title, e.delivery_id, e.status
                  FROM delivery_events e JOIN deliveries d ON d.id = e.delivery_id
                 WHERE e.actor_user_id = :userId AND e.society_id = :societyId
                UNION ALL
                SELECT a.check_in_at, 'STAFF', s.name || ' checked in', a.staff_id, 'INSIDE'
                  FROM domestic_staff_attendance a JOIN domestic_staff s ON s.id = a.staff_id
                 WHERE a.checked_in_by = :userId AND a.society_id = :societyId
                UNION ALL
                SELECT a.check_out_at, 'STAFF', s.name || ' checked out', a.staff_id, 'OUTSIDE'
                  FROM domestic_staff_attendance a JOIN domestic_staff s ON s.id = a.staff_id
                 WHERE a.checked_out_by = :userId AND a.society_id = :societyId AND a.check_out_at IS NOT NULL
                UNION ALL
                SELECT l.started_at, 'DUTY', 'Started duty', l.guard_id, 'ON_DUTY' FROM guard_duty_logs l WHERE l.guard_id = :guardId
                UNION ALL
                SELECT l.ended_at, 'DUTY', 'Ended duty', l.guard_id, 'OFF_DUTY' FROM guard_duty_logs l
                 WHERE l.guard_id = :guardId AND l.ended_at IS NOT NULL
                """;
        List<GuardDtos.GuardActivityItem> items = jdbc.query(
                "SELECT * FROM (" + union + ") x ORDER BY at DESC LIMIT :limit OFFSET :offset", params,
                (rs, i) -> new GuardDtos.GuardActivityItem(rs.getTimestamp("at").toInstant(), rs.getString("kind"),
                        rs.getString("title"), rs.getObject("ref", UUID.class), rs.getString("status")));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + union + ") x", params, Long.class);
        return PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(), total == null ? 0 : total);
    }

    // ------------------------------------------------------------------ helpers

    private GuardAssignment assign(SecurityGuard guard, UUID gateId, String shiftName,
                                   java.time.LocalTime shiftStart, java.time.LocalTime shiftEnd) {
        SocietyGate gate = gateService.requireGate(gateId);
        if (!gate.getSocietyId().equals(guard.getSocietyId())) {
            throw ApiException.field(ErrorCode.GATE_NOT_FOUND, "gateId", "The gate belongs to another society.");
        }
        if (gate.getStatus() != ActiveStatus.ACTIVE) {
            throw ApiException.field(ErrorCode.GATE_INACTIVE, "gateId", ErrorCode.GATE_INACTIVE.defaultMessage());
        }
        Instant now = clock.instant();
        Optional<GuardAssignment> previous = assignments.findByGuardIdAndActiveTrue(guard.getId());
        previous.ifPresent(p -> {
            p.setActive(false);
            p.setValidUntil(now);
        });
        assignments.flush();
        GuardAssignment assignment = new GuardAssignment();
        assignment.setSocietyId(guard.getSocietyId());
        assignment.setGuardId(guard.getId());
        assignment.setGate(gate);
        assignment.setShiftName(Strings.trimToNull(shiftName));
        assignment.setShiftStart(shiftStart);
        assignment.setShiftEnd(shiftEnd);
        assignment.setAssignedBy(access.currentUserId());
        assignment.setValidFrom(now);
        assignments.save(assignment);
        // An on-duty guard moved to another gate continues the shift at the new gate.
        if (guard.isOnDuty()) {
            dutyLogs.findByGuardIdAndEndedAtIsNull(guard.getId()).ifPresent(open -> {
                if (!gate.getId().equals(open.getGateId())) {
                    open.setEndedAt(now);
                    dutyLogs.flush();
                    GuardDutyLog next = new GuardDutyLog();
                    next.setSocietyId(guard.getSocietyId());
                    next.setGuardId(guard.getId());
                    next.setGateId(gate.getId());
                    next.setStartedAt(now);
                    dutyLogs.save(next);
                }
            });
        }
        return assignment;
    }

    private void endDuty(SecurityGuard guard, Instant now) {
        guard.setOnDuty(false);
        guard.setDutyChangedAt(now);
        dutyLogs.findByGuardIdAndEndedAtIsNull(guard.getId()).ifPresent(log -> log.setEndedAt(now));
    }

    private void setMembership(UUID societyId, UUID userId, SocietyMembership.Status status) {
        SocietyMembership membership = memberships.findBySocietyIdAndUserIdAndRole(societyId, userId, Role.GUARD)
                .orElseGet(() -> {
                    SocietyMembership created = new SocietyMembership();
                    created.setSocietyId(societyId);
                    created.setUserId(userId);
                    created.setRole(Role.GUARD);
                    return created;
                });
        membership.setStatus(status);
        memberships.save(membership);
    }

    private SecurityGuard requireGuardEntity(UUID id) {
        SecurityGuard guard = guards.findById(id).orElseThrow(() -> new ApiException(ErrorCode.GUARD_NOT_FOUND));
        access.ensureInScope(guard.getSocietyId(), ErrorCode.GUARD_NOT_FOUND);
        return guard;
    }

    private static GuardDtos.GuardResponse toResponse(SecurityGuard guard, GuardAssignment assignment) {
        User user = guard.getUser();
        return new GuardDtos.GuardResponse(guard.getId(), guard.getSocietyId(), user.getId(), user.getName(), user.getMobile(),
                guard.getEmployeeCode(), guard.getAgencyName(), guard.getStatus(), guard.isOnDuty(), guard.getDutyChangedAt(),
                guard.getPermissions().stream().sorted().toList(),
                assignment == null ? null : GuardDtos.AssignmentView.of(assignment), guard.getCreatedAt());
    }
}
