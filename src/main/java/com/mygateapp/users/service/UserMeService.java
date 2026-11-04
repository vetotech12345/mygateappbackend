package com.mygateapp.users.service;

import com.mygateapp.common.dto.Refs;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.files.service.FileService;
import com.mygateapp.guards.entity.GuardAssignment;
import com.mygateapp.guards.entity.SecurityGuard;
import com.mygateapp.guards.repository.GuardAssignmentRepository;
import com.mygateapp.guards.repository.GuardRepository;
import com.mygateapp.residents.entity.FlatMembership;
import com.mygateapp.residents.repository.FamilyMemberRepository;
import com.mygateapp.residents.repository.FlatMembershipRepository;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.GuardPermission;
import com.mygateapp.security.Role;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.society.repository.SocietyMembershipRepository;
import com.mygateapp.society.repository.SocietyRepository;
import com.mygateapp.users.dto.UserMeResponse;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.repository.UserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Builds the {@code /users/me} document consumed by the Flutter app and the admin dashboard. */
@Service
public class UserMeService {

    private static final List<Role> ROLE_PRECEDENCE = List.of(Role.RESIDENT, Role.GUARD, Role.SOCIETY_ADMIN);

    private final UserRepository users;
    private final SocietyRepository societies;
    private final SocietyMembershipRepository memberships;
    private final FlatMembershipRepository flatMemberships;
    private final FamilyMemberRepository familyMembers;
    private final GuardRepository guards;
    private final GuardAssignmentRepository assignments;
    private final FileService files;
    private final JdbcTemplate jdbc;

    public UserMeService(UserRepository users, SocietyRepository societies, SocietyMembershipRepository memberships,
                         FlatMembershipRepository flatMemberships, FamilyMemberRepository familyMembers,
                         GuardRepository guards, GuardAssignmentRepository assignments, FileService files, JdbcTemplate jdbc) {
        this.users = users;
        this.societies = societies;
        this.memberships = memberships;
        this.flatMemberships = flatMemberships;
        this.familyMembers = familyMembers;
        this.guards = guards;
        this.assignments = assignments;
        this.files = files;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public UserMeResponse build(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        boolean superAdmin = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_roles WHERE user_id = ? AND role_code = 'SUPER_ADMIN')",
                Boolean.class, userId));

        List<SocietyMembership> all = memberships.findByUserIdOrderByCreatedAt(userId);
        Map<UUID, Society> societyById = societies.findAllById(all.stream().map(SocietyMembership::getSocietyId).toList())
                .stream().collect(Collectors.toMap(Society::getId, Function.identity()));
        // Visible memberships: active or pending, in an active society.
        List<SocietyMembership> visible = all.stream()
                .filter(m -> m.getStatus() != SocietyMembership.Status.INACTIVE)
                .filter(m -> societyById.containsKey(m.getSocietyId()) && societyById.get(m.getSocietyId()).isActive())
                .toList();

        UUID contextSocietyId = resolveContext(visible);
        Role role = visible.stream()
                .filter(m -> m.getSocietyId().equals(contextSocietyId))
                .map(SocietyMembership::getRole)
                .min(Comparator.comparingInt(ROLE_PRECEDENCE::indexOf))
                .orElse(superAdmin ? Role.SUPER_ADMIN : null);

        List<String> roles = new ArrayList<>();
        if (superAdmin) {
            roles.add(Role.SUPER_ADMIN.name());
        }
        visible.stream().filter(m -> m.getStatus() == SocietyMembership.Status.ACTIVE)
                .map(m -> m.getRole().name()).distinct().forEach(r -> {
                    if (!roles.contains(r)) {
                        roles.add(r);
                    }
                });

        UserMeResponse.ResidentBlock resident = null;
        UserMeResponse.GuardBlock guard = null;
        if (contextSocietyId != null) {
            Society society = societyById.get(contextSocietyId);
            Refs.SocietyRef societyRef = new Refs.SocietyRef(society.getId(), society.getName(), society.getCity());
            if (role == Role.RESIDENT) {
                resident = residentBlock(userId, societyRef);
            } else if (role == Role.GUARD) {
                guard = guardBlock(userId, societyRef);
            }
        }

        List<UserMeResponse.MembershipView> membershipViews = visible.stream()
                .map(m -> new UserMeResponse.MembershipView(m.getSocietyId(), societyById.get(m.getSocietyId()).getName(),
                        m.getRole(), m.getStatus().name()))
                .toList();

        return new UserMeResponse(user.getId(), user.getName(), user.getMobile(), user.getCountryCode(), user.getEmail(),
                role == null ? null : role.name(), roles, user.getStatus().name(), user.isProfileComplete(),
                files.signedUrl(user.getAvatarFileId()), contextSocietyId, resident, guard, membershipViews);
    }

    private UserMeResponse.ResidentBlock residentBlock(UUID userId, Refs.SocietyRef society) {
        List<FlatMembership> active = flatMemberships.findForUser(userId, Set.of(FlatMembership.Status.ACTIVE)).stream()
                .filter(m -> m.getSocietyId().equals(society.id()) && m.getFlat().isActive())
                .toList();
        List<Refs.FlatRef> flats = active.stream().map(m -> m.getFlat().toRef()).toList();
        Optional<FlatMembership> primary = active.stream().filter(FlatMembership::isPrimary).findFirst()
                .or(() -> active.stream().findFirst());
        List<UserMeResponse.FamilyMemberView> family = active.isEmpty() ? List.of()
                : familyMembers.findByFlatMembershipIdInOrderByCreatedAt(active.stream().map(FlatMembership::getId).toList())
                .stream()
                .map(f -> new UserMeResponse.FamilyMemberView(f.getId(), f.getName(), f.getRelation(), f.getMobile()))
                .toList();
        return new UserMeResponse.ResidentBlock(society, flats,
                primary.map(m -> m.getFlat().getId()).orElse(null),
                primary.map(m -> m.getOccupancy().name()).orElse(null),
                family);
    }

    private UserMeResponse.GuardBlock guardBlock(UUID userId, Refs.SocietyRef society) {
        Optional<SecurityGuard> found = guards.findBySocietyIdAndUserId(society.id(), userId);
        if (found.isEmpty()) {
            return null;
        }
        SecurityGuard guard = found.get();
        Optional<GuardAssignment> assignment = assignments.findByGuardIdAndActiveTrue(guard.getId());
        Refs.GateRef gate = assignment.map(GuardAssignment::getGate)
                .filter(g -> g.getStatus() == com.mygateapp.common.ActiveStatus.ACTIVE)
                .map(g -> new Refs.GateRef(g.getId(), g.getName()))
                .orElse(null);
        List<GuardPermission> permissions = guard.getPermissions().stream().sorted().toList();
        return new UserMeResponse.GuardBlock(guard.getId(), society, gate,
                assignment.map(GuardAssignment::shiftLabel).orElse(null), guard.isOnDuty(), permissions);
    }

    /** X-Society-Id if it matches a visible membership; otherwise the first society by role precedence. */
    private static UUID resolveContext(List<SocietyMembership> visible) {
        if (visible.isEmpty()) {
            return null;
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String header = attributes.getRequest().getHeader(AccessService.SOCIETY_HEADER);
            if (header != null) {
                try {
                    UUID requested = UUID.fromString(header.trim());
                    if (visible.stream().anyMatch(m -> m.getSocietyId().equals(requested))) {
                        return requested;
                    }
                } catch (IllegalArgumentException ignored) {
                    // fall through to the default context
                }
            }
        }
        for (SocietyMembership.Status status : List.of(SocietyMembership.Status.ACTIVE, SocietyMembership.Status.PENDING)) {
            for (Role role : ROLE_PRECEDENCE) {
                Optional<UUID> match = visible.stream()
                        .filter(m -> m.getStatus() == status && m.getRole() == role)
                        .map(SocietyMembership::getSocietyId)
                        .findFirst();
                if (match.isPresent()) {
                    return match.get();
                }
            }
        }
        return visible.getFirst().getSocietyId();
    }
}
