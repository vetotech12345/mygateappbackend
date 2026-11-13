package com.mygateapp.parking.service;

import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.GuardPermission;
import com.mygateapp.security.Permission;
import com.mygateapp.security.UserAccess;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Parking-specific authorization on top of {@link AccessService}: who is reading parking data of the current society
 * (admin, guard or resident) and how far they can see. Writes use the narrower helpers.
 */
@Component
public class ParkingAccess {

    public enum Viewer { ADMIN, GUARD, RESIDENT }

    /** {@code flatIds} is only meaningful for residents. */
    public record Scope(UUID societyId, Viewer viewer, Set<UUID> flatIds) {

        public boolean admin() {
            return viewer == Viewer.ADMIN;
        }

        public boolean guard() {
            return viewer == Viewer.GUARD;
        }

        public boolean resident() {
            return viewer == Viewer.RESIDENT;
        }

        public boolean canSeeFlat(UUID flatId) {
            return !resident() || (flatId != null && flatIds.contains(flatId));
        }
    }

    private final AccessService access;

    public ParkingAccess(AccessService access) {
        this.access = access;
    }

    public AccessService core() {
        return access;
    }

    public UUID societyId() {
        return access.currentSocietyId();
    }

    /**
     * Read scope: admins holding {@code adminPermission} see the whole society; guards with the vehicle toggle see the
     * whole society when {@code guardsAllowed}; residents see their own flats. Anyone else gets 403.
     */
    public Scope readScope(Permission adminPermission, boolean guardsAllowed) {
        UUID societyId = access.currentSocietyId();
        if (access.hasPermission(societyId, adminPermission)) {
            return new Scope(societyId, Viewer.ADMIN, Set.of());
        }
        if (guardsAllowed && isVehicleGuard(societyId)) {
            return new Scope(societyId, Viewer.GUARD, Set.of());
        }
        if (access.hasPermission(societyId, Permission.PARKING_VIEW_OWN)) {
            return new Scope(societyId, Viewer.RESIDENT, access.access().flatIdsIn(societyId));
        }
        throw new ApiException(ErrorCode.ACCESS_DENIED);
    }

    /** Residents' own-data endpoints ({@code /mine}). */
    public Scope residentScope() {
        UUID societyId = access.currentSocietyId();
        if (!access.hasPermission(societyId, Permission.PARKING_VIEW_OWN)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        return new Scope(societyId, Viewer.RESIDENT, access.access().flatIdsIn(societyId));
    }

    /** Admin operation in the current society. */
    public UUID requireAdmin(Permission permission) {
        UUID societyId = access.currentSocietyId();
        access.require(societyId, permission);
        return societyId;
    }

    /** Any member allowed to read general parking information (config, rates, availability). */
    public UUID requireViewer() {
        UUID societyId = access.currentSocietyId();
        access.require(societyId, Permission.PARKING_VIEW);
        return societyId;
    }

    /** Gate writes: active, on-duty guard of the current society, assigned to a gate, with VEHICLE_ENTRY. */
    public UserAccess.GuardAccess requireGateGuard() {
        return access.requireOnDutyGuard(GuardPermission.VEHICLE_ENTRY);
    }

    /** Guard reads (dashboard, search, slot map): duty is not required, the vehicle toggle is. */
    public UserAccess.GuardAccess requireVehicleGuard() {
        UUID societyId = access.currentSocietyId();
        UserAccess.GuardAccess guard = access.requireGuard(societyId);
        if (!isVehicleGuard(societyId)) {
            throw new ApiException(ErrorCode.GUARD_PERMISSION_DENIED);
        }
        return guard;
    }

    public boolean isVehicleGuard(UUID societyId) {
        return access.access().guardIn(societyId)
                .filter(g -> g.permissions().contains(GuardPermission.VEHICLE_ENTRY))
                .isPresent() && access.hasPermission(societyId, Permission.GATE_VEHICLE_OPERATE);
    }

    /** The caller's active membership of {@code flatId} with a resident permission. */
    public UserAccess.FlatAccess requireResidentFlat(UUID flatId, Permission permission) {
        return access.requireResidentFlat(flatId, permission);
    }

    public UUID currentUserId() {
        return access.currentUserId();
    }

    /** 404 for a record of another society (existence is not revealed). */
    public void ensureSameSociety(UUID recordSocietyId, UUID societyId, ErrorCode notFound) {
        if (!recordSocietyId.equals(societyId)) {
            throw new ApiException(notFound);
        }
    }
}
