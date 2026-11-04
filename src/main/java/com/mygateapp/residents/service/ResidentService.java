package com.mygateapp.residents.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.MobileNumbers;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.flats.service.FlatService;
import com.mygateapp.notifications.enums.NotificationType;
import com.mygateapp.notifications.service.NotificationService;
import com.mygateapp.residents.dto.ResidentDtos;
import com.mygateapp.residents.entity.FamilyMember;
import com.mygateapp.residents.entity.FlatMembership;
import com.mygateapp.residents.repository.FamilyMemberRepository;
import com.mygateapp.residents.repository.FlatMembershipRepository;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.Permission;
import com.mygateapp.security.Role;
import com.mygateapp.security.UserAccess;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.society.repository.SocietyMembershipRepository;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.repository.UserRepository;
import com.mygateapp.users.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Residents and their flat memberships. The API "resident" resource is a flat membership: one person linked to one
 * flat as OWNER or TENANT. Ended memberships remain as occupancy history.
 */
@Service
public class ResidentService {

    private static final Set<FlatMembership.Status> OPEN = Set.of(FlatMembership.Status.PENDING, FlatMembership.Status.ACTIVE);
    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "status", "startDate");
    private static final int MAX_FAMILY_MEMBERS = 15;

    private final FlatMembershipRepository flatMemberships;
    private final FamilyMemberRepository familyMembers;
    private final SocietyMembershipRepository societyMemberships;
    private final UserRepository users;
    private final UserService userService;
    private final FlatService flatService;
    private final NotificationService notifications;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public ResidentService(FlatMembershipRepository flatMemberships, FamilyMemberRepository familyMembers,
                           SocietyMembershipRepository societyMemberships, UserRepository users, UserService userService,
                           FlatService flatService, NotificationService notifications, AccessService access,
                           AuditService audit, Clock clock) {
        this.flatMemberships = flatMemberships;
        this.familyMembers = familyMembers;
        this.societyMemberships = societyMemberships;
        this.users = users;
        this.userService = userService;
        this.flatService = flatService;
        this.notifications = notifications;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    // ----------------------------------------------------------------- creation

    @Transactional
    public ResidentDtos.ResidentResponse create(ResidentDtos.CreateResidentRequest request) {
        Flat flat = flatService.requireFlat(request.flatId());
        if (!flat.isActive()) {
            throw ApiException.field(ErrorCode.INVALID_FLAT, "flatId", "This flat is not active.");
        }
        boolean admin = access.hasPermission(flat.getSocietyId(), Permission.RESIDENT_MANAGE);
        if (!admin) {
            UserAccess.FlatAccess own = access.requireResidentFlat(flat.getId(), Permission.RESIDENT_REQUEST);
            if (!FlatMembership.Occupancy.OWNER.name().equals(own.occupancy())) {
                throw new ApiException(ErrorCode.NOT_FLAT_OWNER);
            }
        }
        User user = userService.findOrCreate(request.countryCode(), request.mobile(), request.name(), request.email());
        if (flatMemberships.existsOpen(flat.getId(), user.getId(), OPEN)) {
            throw new ApiException(ErrorCode.RESIDENT_ALREADY_LINKED);
        }
        Instant now = clock.instant();
        FlatMembership membership = new FlatMembership();
        membership.setSocietyId(flat.getSocietyId());
        membership.setFlat(flat);
        membership.setUser(user);
        membership.setOccupancy(request.occupancy());
        membership.setRequestedBy(access.currentUserId());
        membership.setStartDate(request.startDate() != null ? request.startDate() : today(flat));
        if (admin) {
            membership.setStatus(FlatMembership.Status.ACTIVE);
            membership.setApprovedBy(access.currentUserId());
            membership.setApprovedAt(now);
        } else {
            membership.setStatus(FlatMembership.Status.PENDING);
        }
        flatMemberships.save(membership);
        if (admin) {
            applyPrimary(membership, Boolean.TRUE.equals(request.primary()));
        }
        syncSocietyMembership(flat.getSocietyId(), user.getId());

        String label = flat.toRef().label();
        if (admin) {
            audit.record(flat.getSocietyId(), "RESIDENT_CREATED", "RESIDENT", membership.getId(),
                    user.displayName() + " added to " + label + " as " + request.occupancy(),
                    Map.of("userId", user.getId(), "flat", label));
        } else {
            audit.record(flat.getSocietyId(), "RESIDENT_REQUESTED", "RESIDENT", membership.getId(),
                    "Request to add " + user.displayName() + " to " + label, Map.of("flat", label));
            List<UUID> admins = societyMemberships.findBySocietyIdAndRoleAndStatus(flat.getSocietyId(), Role.SOCIETY_ADMIN,
                    SocietyMembership.Status.ACTIVE).stream().map(SocietyMembership::getUserId).toList();
            notifications.notifyUsers(admins, flat.getSocietyId(), NotificationType.GENERAL, "New resident request",
                    user.displayName() + " is waiting for approval for " + label + ".", null, null);
        }
        return toResponse(membership, List.of());
    }

    // ----------------------------------------------------------- admin decisions

    @Transactional
    public ResidentDtos.ResidentResponse approve(UUID id) {
        FlatMembership membership = requireForManage(id);
        if (membership.getStatus() != FlatMembership.Status.PENDING) {
            throw new ApiException(ErrorCode.RESIDENT_NOT_PENDING);
        }
        membership.setStatus(FlatMembership.Status.ACTIVE);
        membership.setApprovedBy(access.currentUserId());
        membership.setApprovedAt(clock.instant());
        if (membership.getStartDate() == null) {
            membership.setStartDate(today(membership.getFlat()));
        }
        applyPrimary(membership, false);
        syncSocietyMembership(membership.getSocietyId(), membership.getUser().getId());
        String label = membership.getFlat().toRef().label();
        audit.record(membership.getSocietyId(), "RESIDENT_APPROVED", "RESIDENT", id,
                membership.getUser().displayName() + " approved for " + label, Map.of());
        notifications.notifyUsers(List.of(membership.getUser().getId()), membership.getSocietyId(), NotificationType.GENERAL,
                "Residence approved", "You can now use the app for " + label + ".", null, null);
        return toResponse(membership, null);
    }

    @Transactional
    public ResidentDtos.ResidentResponse reject(UUID id, ResidentDtos.DecisionRequest request) {
        FlatMembership membership = requireForManage(id);
        if (membership.getStatus() != FlatMembership.Status.PENDING) {
            throw new ApiException(ErrorCode.RESIDENT_NOT_PENDING);
        }
        membership.setStatus(FlatMembership.Status.REJECTED);
        membership.setEndReason(request == null ? null : Strings.trimToNull(request.reason()));
        syncSocietyMembership(membership.getSocietyId(), membership.getUser().getId());
        audit.record(membership.getSocietyId(), "RESIDENT_REJECTED", "RESIDENT", id,
                "Resident request for " + membership.getFlat().toRef().label() + " rejected",
                Map.of("reason", membership.getEndReason() == null ? "" : membership.getEndReason()));
        return toResponse(membership, null);
    }

    /** Ends an active membership (move-out). The row is kept as occupancy history. */
    @Transactional
    public ResidentDtos.ResidentResponse end(UUID id, ResidentDtos.EndResidencyRequest request) {
        FlatMembership membership = requireForManage(id);
        if (membership.getStatus() != FlatMembership.Status.ACTIVE) {
            throw new ApiException(ErrorCode.RESIDENT_NOT_ACTIVE);
        }
        endMembership(membership, request == null ? null : request.endDate(), request == null ? null : request.reason());
        audit.record(membership.getSocietyId(), "RESIDENT_ENDED", "RESIDENT", id,
                membership.getUser().displayName() + " moved out of " + membership.getFlat().toRef().label(),
                Map.of("endDate", membership.getEndDate().toString()));
        return toResponse(membership, null);
    }

    /** Moves a resident to another flat of the same society: ends the current membership and creates a new one. */
    @Transactional
    public ResidentDtos.ResidentResponse transfer(UUID id, ResidentDtos.TransferRequest request) {
        FlatMembership current = requireForManage(id);
        if (current.getStatus() != FlatMembership.Status.ACTIVE) {
            throw new ApiException(ErrorCode.RESIDENT_NOT_ACTIVE);
        }
        Flat target = flatService.requireFlat(request.flatId());
        if (!target.getSocietyId().equals(current.getSocietyId()) || !target.isActive()) {
            throw ApiException.field(ErrorCode.INVALID_FLAT, "flatId", "Select an active flat of the same society.");
        }
        if (flatMemberships.existsOpen(target.getId(), current.getUser().getId(), OPEN)) {
            throw new ApiException(ErrorCode.RESIDENT_ALREADY_LINKED);
        }
        boolean wasPrimary = current.isPrimary();
        LocalDate start = request.startDate() != null ? request.startDate() : today(target);
        endMembership(current, start, request.reason() != null ? request.reason() : "Transferred");
        FlatMembership next = new FlatMembership();
        next.setSocietyId(target.getSocietyId());
        next.setFlat(target);
        next.setUser(current.getUser());
        next.setOccupancy(request.occupancy() != null ? request.occupancy() : current.getOccupancy());
        next.setStatus(FlatMembership.Status.ACTIVE);
        next.setRequestedBy(access.currentUserId());
        next.setApprovedBy(access.currentUserId());
        next.setApprovedAt(clock.instant());
        next.setStartDate(start);
        flatMemberships.save(next);
        applyPrimary(next, wasPrimary);
        syncSocietyMembership(target.getSocietyId(), current.getUser().getId());
        audit.record(target.getSocietyId(), "RESIDENT_TRANSFERRED", "RESIDENT", next.getId(),
                current.getUser().displayName() + " moved from " + current.getFlat().toRef().label() + " to "
                        + target.toRef().label(), Map.of("previousMembershipId", current.getId()));
        return toResponse(next, List.of());
    }

    @Transactional
    public ResidentDtos.ResidentResponse update(UUID id, ResidentDtos.UpdateResidentRequest request) {
        FlatMembership membership = requireForManage(id);
        User user = membership.getUser();
        if (request.name() != null) {
            user.setName(request.name().trim());
        }
        if (request.email() != null) {
            user.setEmail(Strings.trimToNull(request.email()) == null ? null : request.email().trim().toLowerCase());
        }
        if (request.occupancy() != null) {
            membership.setOccupancy(request.occupancy());
        }
        if (Boolean.TRUE.equals(request.primary()) && membership.isActive()) {
            applyPrimary(membership, true);
        }
        audit.record(membership.getSocietyId(), "RESIDENT_UPDATED", "RESIDENT", id,
                "Resident " + user.displayName() + " updated", Map.of("occupancy", membership.getOccupancy().name()));
        return toResponse(membership, null);
    }

    // -------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public ResidentDtos.ResidentResponse get(UUID id) {
        FlatMembership membership = flatMemberships.findById(id).orElseThrow(() -> new ApiException(ErrorCode.RESIDENT_NOT_FOUND));
        access.ensureInScope(membership.getSocietyId(), ErrorCode.RESIDENT_NOT_FOUND);
        boolean self = membership.getUser().getId().equals(access.currentUserId());
        if (!self && !access.hasPermission(membership.getSocietyId(), Permission.RESIDENT_VIEW)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        return toResponse(membership, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<ResidentDtos.ResidentResponse> search(UUID societyIdParam, FlatMembership.Status status, UUID towerId,
                                                              UUID flatId, String q, Integer page, Integer size, String sort) {
        UUID societyId = access.resolveSocietyId(societyIdParam);
        access.require(societyId, Permission.RESIDENT_VIEW);
        var pageable = Pages.of(page, size, sort, SORT_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(flatMemberships.search(societyId, status, towerId, flatId, Strings.likePattern(q), pageable),
                m -> toResponse(m, List.of()));
    }

    /** {@code GET /residents/me}: the caller's flats (active and pending) with family members. */
    @Transactional(readOnly = true)
    public ResidentDtos.MyResidentProfile me() {
        User user = users.findById(access.currentUserId()).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        List<FlatMembership> mine = flatMemberships.findForUser(user.getId(), OPEN);
        Map<UUID, List<FamilyMember>> family = familyByMembership(mine.stream().map(FlatMembership::getId).toList());
        return new ResidentDtos.MyResidentProfile(user.getId(), user.getName(), user.getMobile(), user.getEmail(),
                mine.stream().map(m -> toResponse(m, family.getOrDefault(m.getId(), List.of()))).toList());
    }

    // ------------------------------------------------------------------- family

    @Transactional(readOnly = true)
    public List<ResidentDtos.FamilyMemberResponse> myFamily() {
        List<FlatMembership> mine = flatMemberships.findForUser(access.currentUserId(), Set.of(FlatMembership.Status.ACTIVE));
        Map<UUID, UUID> flatByMembership = mine.stream()
                .collect(Collectors.toMap(FlatMembership::getId, m -> m.getFlat().getId()));
        return familyMembers.findByFlatMembershipIdInOrderByCreatedAt(flatByMembership.keySet().isEmpty()
                        ? List.of(com.mygateapp.common.Ids.NIL) : flatByMembership.keySet()).stream()
                .map(f -> toFamily(f, flatByMembership.get(f.getFlatMembershipId())))
                .toList();
    }

    @Transactional
    public ResidentDtos.FamilyMemberResponse addFamilyMember(ResidentDtos.AddFamilyMemberRequest request) {
        List<FlatMembership> mine = flatMemberships.findForUser(access.currentUserId(), Set.of(FlatMembership.Status.ACTIVE));
        FlatMembership membership = (request.flatId() == null
                ? mine.stream().filter(FlatMembership::isPrimary).findFirst().or(() -> mine.stream().findFirst())
                : mine.stream().filter(m -> m.getFlat().getId().equals(request.flatId())).findFirst())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FLAT_RESIDENT));
        access.requireResidentFlat(membership.getFlat().getId(), Permission.FAMILY_MANAGE);
        if (familyMembers.countByFlatMembershipId(membership.getId()) >= MAX_FAMILY_MEMBERS) {
            throw ApiException.field("name", "A household can have at most " + MAX_FAMILY_MEMBERS + " family members");
        }
        FamilyMember member = new FamilyMember();
        member.setFlatMembershipId(membership.getId());
        member.setSocietyId(membership.getSocietyId());
        member.setName(request.name().trim());
        member.setRelation(request.relation().trim());
        member.setMobile(MobileNumbers.normalizeOptional(request.mobile(), "mobile"));
        familyMembers.save(member);
        return toFamily(member, membership.getFlat().getId());
    }

    @Transactional
    public void removeFamilyMember(UUID familyMemberId) {
        FamilyMember member = familyMembers.findById(familyMemberId)
                .orElseThrow(() -> new ApiException(ErrorCode.FAMILY_MEMBER_NOT_FOUND));
        FlatMembership membership = flatMemberships.findById(member.getFlatMembershipId())
                .orElseThrow(() -> new ApiException(ErrorCode.FAMILY_MEMBER_NOT_FOUND));
        if (!membership.getUser().getId().equals(access.currentUserId())) {
            throw new ApiException(ErrorCode.FAMILY_MEMBER_NOT_FOUND);
        }
        familyMembers.delete(member);
    }

    // ------------------------------------------------------------------ helpers

    private FlatMembership requireForManage(UUID id) {
        FlatMembership membership = flatMemberships.findById(id).orElseThrow(() -> new ApiException(ErrorCode.RESIDENT_NOT_FOUND));
        access.ensureInScope(membership.getSocietyId(), ErrorCode.RESIDENT_NOT_FOUND);
        access.require(membership.getSocietyId(), Permission.RESIDENT_MANAGE);
        return membership;
    }

    private void endMembership(FlatMembership membership, LocalDate endDate, String reason) {
        membership.setStatus(FlatMembership.Status.ENDED);
        LocalDate end = endDate != null ? endDate : today(membership.getFlat());
        if (membership.getStartDate() != null && end.isBefore(membership.getStartDate())) {
            throw ApiException.field("endDate", "The end date cannot be before the start date");
        }
        membership.setEndDate(end);
        membership.setEndReason(Strings.trimToNull(reason));
        membership.setPrimary(false);
        syncSocietyMembership(membership.getSocietyId(), membership.getUser().getId());
    }

    /** Makes {@code membership} the user's primary flat when requested or when they have no other primary flat. */
    private void applyPrimary(FlatMembership membership, boolean requested) {
        List<FlatMembership> others = flatMemberships.findForUser(membership.getUser().getId(), Set.of(FlatMembership.Status.ACTIVE))
                .stream().filter(m -> !m.getId().equals(membership.getId())).toList();
        boolean hasPrimary = others.stream().anyMatch(FlatMembership::isPrimary);
        if (requested || !hasPrimary) {
            others.forEach(m -> m.setPrimary(false));
            membership.setPrimary(true);
        }
    }

    /**
     * Keeps the society-level RESIDENT membership in step with the user's flats: ACTIVE with any active flat, PENDING
     * with only pending requests, INACTIVE otherwise.
     */
    private void syncSocietyMembership(UUID societyId, UUID userId) {
        long active = flatMemberships.countByUserIdAndSocietyIdAndStatus(userId, societyId, FlatMembership.Status.ACTIVE);
        long pending = flatMemberships.countByUserIdAndSocietyIdAndStatus(userId, societyId, FlatMembership.Status.PENDING);
        SocietyMembership.Status target = active > 0 ? SocietyMembership.Status.ACTIVE
                : pending > 0 ? SocietyMembership.Status.PENDING : SocietyMembership.Status.INACTIVE;
        SocietyMembership membership = societyMemberships.findBySocietyIdAndUserIdAndRole(societyId, userId, Role.RESIDENT)
                .orElse(null);
        if (membership == null) {
            if (target == SocietyMembership.Status.INACTIVE) {
                return;
            }
            membership = new SocietyMembership();
            membership.setSocietyId(societyId);
            membership.setUserId(userId);
            membership.setRole(Role.RESIDENT);
        }
        membership.setStatus(target);
        societyMemberships.save(membership);
        if (userId.equals(currentUserIdOrNull())) {
            access.invalidate();
        }
    }

    private UUID currentUserIdOrNull() {
        try {
            return access.currentUserId();
        } catch (ApiException e) {
            return null;
        }
    }

    private Map<UUID, List<FamilyMember>> familyByMembership(List<UUID> membershipIds) {
        if (membershipIds.isEmpty()) {
            return Map.of();
        }
        return familyMembers.findByFlatMembershipIdInOrderByCreatedAt(membershipIds).stream()
                .collect(Collectors.groupingBy(FamilyMember::getFlatMembershipId));
    }

    /** {@code family == null} loads the family members of this membership. */
    private ResidentDtos.ResidentResponse toResponse(FlatMembership m, List<FamilyMember> family) {
        List<FamilyMember> members = family != null ? family
                : familyMembers.findByFlatMembershipIdInOrderByCreatedAt(List.of(m.getId()));
        User u = m.getUser();
        return new ResidentDtos.ResidentResponse(m.getId(), u.getId(), u.getName(), u.getMobile(), u.getEmail(),
                m.getFlat().toRef(), m.getOccupancy(), m.getStatus(), m.isPrimary(), m.getStartDate(), m.getEndDate(),
                m.getEndReason(), m.getApprovedAt(), m.getCreatedAt(),
                members.stream().map(f -> toFamily(f, m.getFlat().getId())).toList());
    }

    private static ResidentDtos.FamilyMemberResponse toFamily(FamilyMember f, UUID flatId) {
        return new ResidentDtos.FamilyMemberResponse(f.getId(), flatId, f.getName(), f.getRelation(), f.getMobile());
    }

    private LocalDate today(Flat flat) {
        return LocalDate.now(clock);
    }
}
