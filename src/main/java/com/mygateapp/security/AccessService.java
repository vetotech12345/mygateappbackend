package com.mygateapp.security;

import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Central authorization service: who the caller is, which societies / flats / gates they may act on, and
 * permission checks scoped to a society. Every service that reads or changes society data goes through here;
 * nothing relies on the client hiding buttons.
 *
 * <p>Error semantics: a resource in a society the caller does not belong to is reported as NOT FOUND (its existence
 * is not revealed). A resource in the caller's society that they may not act on is FORBIDDEN.
 */
@Service
public class AccessService {

    public static final String SOCIETY_HEADER = "X-Society-Id";
    private static final String CACHE_ATTRIBUTE = AccessService.class.getName() + ".ACCESS";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final RolePermissionRegistry registry;

    public AccessService(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, RolePermissionRegistry registry) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.registry = registry;
    }

    // ------------------------------------------------------------------ identity

    public AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        throw new ApiException(ErrorCode.UNAUTHORIZED);
    }

    public UUID currentUserId() {
        return currentUser().userId();
    }

    /** Access data for the current request (cached per request). */
    public UserAccess access() {
        UUID userId = currentUserId();
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            Object cached = attributes.getAttribute(CACHE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof UserAccess access && access.userId().equals(userId)) {
                return access;
            }
        }
        UserAccess access = load(userId);
        if (attributes != null) {
            attributes.setAttribute(CACHE_ATTRIBUTE, access, RequestAttributes.SCOPE_REQUEST);
        }
        return access;
    }

    /** Call after changing the current user's memberships/duty within the same request. */
    public void invalidate() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.removeAttribute(CACHE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        }
    }

    // ------------------------------------------------------------- society scope

    /** Society context from {@code X-Society-Id}, or the user's default society. */
    public UUID currentSocietyId() {
        UserAccess access = access();
        String header = headerValue();
        if (header != null) {
            UUID societyId;
            try {
                societyId = UUID.fromString(header.trim());
            } catch (IllegalArgumentException ex) {
                throw ApiException.field(ErrorCode.INVALID_REQUEST, SOCIETY_HEADER, "X-Society-Id must be a UUID");
            }
            if (access.superAdmin() || access.isMemberOf(societyId)) {
                return societyId;
            }
            throw new ApiException(ErrorCode.SOCIETY_ACCESS_DENIED);
        }
        return access.defaultSocietyId().orElseThrow(() -> access.superAdmin()
                ? new ApiException(ErrorCode.SOCIETY_CONTEXT_REQUIRED)
                : new ApiException(ErrorCode.NO_ACTIVE_MEMBERSHIP));
    }

    /** Explicit society id (admin APIs) validated against the caller, or the current society when null. */
    public UUID resolveSocietyId(UUID explicitSocietyId) {
        if (explicitSocietyId == null) {
            return currentSocietyId();
        }
        UserAccess access = access();
        if (access.superAdmin() || access.isMemberOf(explicitSocietyId)) {
            return explicitSocietyId;
        }
        throw new ApiException(ErrorCode.SOCIETY_ACCESS_DENIED);
    }

    public boolean hasPermission(UUID societyId, Permission permission) {
        UserAccess access = access();
        if (access.superAdmin() && registry.has(Role.SUPER_ADMIN, permission)) {
            return true;
        }
        return access.memberships().stream()
                .anyMatch(m -> m.societyId().equals(societyId) && registry.has(m.role(), permission));
    }

    public boolean hasPlatformPermission(Permission permission) {
        return access().superAdmin() && registry.has(Role.SUPER_ADMIN, permission);
    }

    /** 403 unless the caller has {@code permission} in {@code societyId}. */
    public void require(UUID societyId, Permission permission) {
        if (!hasPermission(societyId, permission)) {
            UserAccess access = access();
            throw new ApiException(access.superAdmin() || access.isMemberOf(societyId)
                    ? ErrorCode.ACCESS_DENIED : ErrorCode.SOCIETY_ACCESS_DENIED);
        }
    }

    public void requirePlatform(Permission permission) {
        if (!hasPlatformPermission(permission)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
    }

    /**
     * Object-level tenant check for a resource loaded by id: outside the caller's societies it is reported as
     * {@code notFound}, so ids from other societies cannot be probed.
     */
    public void ensureInScope(UUID resourceSocietyId, ErrorCode notFound) {
        UserAccess access = access();
        if (!access.superAdmin() && !access.isMemberOf(resourceSocietyId)) {
            throw new ApiException(notFound);
        }
    }

    // --------------------------------------------------------------- residents

    /** The caller's active membership of {@code flatId}, with the resident permission checked. */
    public UserAccess.FlatAccess requireResidentFlat(UUID flatId, Permission permission) {
        UserAccess.FlatAccess flat = access().flat(flatId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FLAT_RESIDENT));
        if (!registry.has(Role.RESIDENT, permission) || !access().hasRole(flat.societyId(), Role.RESIDENT)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        return flat;
    }

    public boolean isResidentOf(UUID flatId) {
        return access().flat(flatId).isPresent();
    }

    // ------------------------------------------------------------------- guards

    public UserAccess.GuardAccess requireGuard(UUID societyId) {
        return access().guardIn(societyId).orElseThrow(() -> new ApiException(ErrorCode.NOT_A_GUARD));
    }

    /**
     * Gate operations: the caller must be an active guard of the current society, have the role permission and the
     * per-guard toggle, be on duty, and be assigned to an active gate.
     */
    public UserAccess.GuardAccess requireOnDutyGuard(GuardPermission permission) {
        UUID societyId = currentSocietyId();
        UserAccess.GuardAccess guard = requireGuard(societyId);
        if (!hasPermission(societyId, permission.rolePermission()) || !guard.permissions().contains(permission)) {
            throw new ApiException(ErrorCode.GUARD_PERMISSION_DENIED);
        }
        if (!guard.onDuty()) {
            throw new ApiException(ErrorCode.GUARD_OFF_DUTY);
        }
        if (guard.gateId() == null) {
            throw new ApiException(ErrorCode.GUARD_NO_GATE);
        }
        return guard;
    }

    // -------------------------------------------------------------------- loading

    private UserAccess load(UUID userId) {
        Boolean superAdmin = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_roles WHERE user_id = ? AND role_code = 'SUPER_ADMIN')",
                Boolean.class, userId);

        List<UserAccess.Membership> memberships = jdbc.query("""
                SELECT m.society_id, m.role_code, m.created_at
                FROM society_memberships m JOIN societies s ON s.id = m.society_id
                WHERE m.user_id = ? AND m.status = 'ACTIVE' AND s.status = 'ACTIVE'
                ORDER BY m.created_at
                """, (rs, i) -> new UserAccess.Membership(rs.getObject(1, UUID.class), Role.valueOf(rs.getString(2)),
                rs.getTimestamp(3).toInstant()), userId);

        List<UserAccess.FlatAccess> flats = jdbc.query("""
                SELECT fm.id, fm.flat_id, fm.society_id, f.tower_id, fm.occupancy_type, fm.is_primary
                FROM flat_memberships fm
                JOIN flats f ON f.id = fm.flat_id
                JOIN societies s ON s.id = fm.society_id
                JOIN society_memberships sm ON sm.user_id = fm.user_id AND sm.society_id = fm.society_id
                     AND sm.role_code = 'RESIDENT' AND sm.status = 'ACTIVE'
                WHERE fm.user_id = ? AND fm.status = 'ACTIVE' AND f.status = 'ACTIVE' AND s.status = 'ACTIVE'
                ORDER BY fm.is_primary DESC, fm.created_at
                """, (rs, i) -> new UserAccess.FlatAccess(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getBoolean(6)), userId);

        record GuardRow(UUID guardId, UUID societyId, UUID gateId, boolean onDuty) {
        }
        List<GuardRow> guardRows = jdbc.query("""
                SELECT g.id, g.society_id, sg.id, g.on_duty
                FROM security_guards g
                JOIN societies s ON s.id = g.society_id
                JOIN society_memberships sm ON sm.user_id = g.user_id AND sm.society_id = g.society_id
                     AND sm.role_code = 'GUARD' AND sm.status = 'ACTIVE'
                LEFT JOIN guard_assignments a ON a.guard_id = g.id AND a.active
                LEFT JOIN society_gates sg ON sg.id = a.gate_id AND sg.status = 'ACTIVE'
                WHERE g.user_id = ? AND g.status = 'ACTIVE' AND s.status = 'ACTIVE'
                """, (rs, i) -> new GuardRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getBoolean(4)), userId);

        Map<UUID, Set<GuardPermission>> guardPermissions = new HashMap<>();
        if (!guardRows.isEmpty()) {
            List<UUID> ids = guardRows.stream().map(GuardRow::guardId).toList();
            namedJdbc.query("SELECT guard_id, permission FROM guard_permissions WHERE guard_id IN (:ids)",
                    new MapSqlParameterSource("ids", ids), rs -> {
                        guardPermissions.computeIfAbsent(rs.getObject(1, UUID.class), k -> EnumSet.noneOf(GuardPermission.class))
                                .add(GuardPermission.valueOf(rs.getString(2)));
                    });
        }
        List<UserAccess.GuardAccess> guards = new ArrayList<>();
        for (GuardRow row : guardRows) {
            guards.add(new UserAccess.GuardAccess(row.guardId(), row.societyId(), row.gateId(), row.onDuty(),
                    guardPermissions.getOrDefault(row.guardId(), Set.of())));
        }
        return new UserAccess(userId, Boolean.TRUE.equals(superAdmin), memberships, flats, guards);
    }

    private static String headerValue() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            String value = request.getHeader(SOCIETY_HEADER);
            return value == null || value.isBlank() ? null : value;
        }
        return null;
    }
}
