package com.mygateapp.security;

/**
 * Per-guard capability toggles managed by the society admin. Effective rights are the GUARD role's permission
 * AND the guard's toggle. Values match the Flutter {@code GuardPermission} enum.
 */
public enum GuardPermission {
    VISITOR_ENTRY(Permission.GATE_VISITOR_OPERATE),
    DELIVERY_ENTRY(Permission.GATE_DELIVERY_OPERATE),
    STAFF_ATTENDANCE(Permission.GATE_STAFF_OPERATE),
    /** Vehicle verification, parking entry / exit and gate payments (V9). Not granted to existing guards automatically. */
    VEHICLE_ENTRY(Permission.GATE_VEHICLE_OPERATE);

    private final Permission rolePermission;

    GuardPermission(Permission rolePermission) {
        this.rolePermission = rolePermission;
    }

    public Permission rolePermission() {
        return rolePermission;
    }
}
