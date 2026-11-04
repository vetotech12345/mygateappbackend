package com.mygateapp.security;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Everything the authorization layer needs to know about the current user, loaded once per request from the
 * database. Only ACTIVE memberships in ACTIVE societies are included.
 */
public record UserAccess(
        UUID userId,
        boolean superAdmin,
        List<Membership> memberships,
        List<FlatAccess> flats,
        List<GuardAccess> guards) {

    public record Membership(UUID societyId, Role role, Instant since) {
    }

    public record FlatAccess(UUID flatMembershipId, UUID flatId, UUID societyId, UUID towerId, String occupancy, boolean primary) {
    }

    public record GuardAccess(UUID guardId, UUID societyId, UUID gateId, boolean onDuty, Set<GuardPermission> permissions) {
    }

    private static final List<Role> CONTEXT_PRECEDENCE = List.of(Role.RESIDENT, Role.GUARD, Role.SOCIETY_ADMIN);

    public boolean hasRole(UUID societyId, Role role) {
        return memberships.stream().anyMatch(m -> m.societyId().equals(societyId) && m.role() == role);
    }

    public boolean hasRoleAnywhere(Role role) {
        return (role == Role.SUPER_ADMIN && superAdmin) || memberships.stream().anyMatch(m -> m.role() == role);
    }

    public boolean isMemberOf(UUID societyId) {
        return memberships.stream().anyMatch(m -> m.societyId().equals(societyId));
    }

    public Set<UUID> societyIds() {
        return memberships.stream().map(Membership::societyId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public List<FlatAccess> flatsIn(UUID societyId) {
        return flats.stream().filter(f -> f.societyId().equals(societyId)).toList();
    }

    public Set<UUID> flatIdsIn(UUID societyId) {
        return flats.stream().filter(f -> f.societyId().equals(societyId)).map(FlatAccess::flatId).collect(Collectors.toSet());
    }

    public Optional<FlatAccess> flat(UUID flatId) {
        return flats.stream().filter(f -> f.flatId().equals(flatId)).findFirst();
    }

    public Optional<GuardAccess> guardIn(UUID societyId) {
        return guards.stream().filter(g -> g.societyId().equals(societyId)).findFirst();
    }

    /** Default society: the first society (oldest membership) by role precedence RESIDENT, GUARD, SOCIETY_ADMIN. */
    public Optional<UUID> defaultSocietyId() {
        for (Role role : CONTEXT_PRECEDENCE) {
            Optional<UUID> match = memberships.stream()
                    .filter(m -> m.role() == role)
                    .min(Comparator.comparing(Membership::since))
                    .map(Membership::societyId);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }
}
