package com.mygateapp.security;

/**
 * Fine-grained permissions checked in services, always together with a society scope. Role grants come from the
 * {@code role_permissions} table (see V8 migration) via {@link RolePermissionRegistry}.
 */
public enum Permission {
    // Platform
    PLATFORM_SOCIETY_CREATE,
    PLATFORM_SOCIETY_STATUS,
    PLATFORM_ADMIN_MANAGE,
    PLATFORM_STATS_VIEW,
    PLATFORM_SETTINGS_MANAGE,
    PLATFORM_USER_STATUS,
    // Society administration
    SOCIETY_VIEW,
    SOCIETY_UPDATE,
    STRUCTURE_MANAGE,
    RESIDENT_VIEW,
    RESIDENT_MANAGE,
    GUARD_MANAGE,
    GATE_MANAGE,
    VISITOR_LOG_VIEW,
    DELIVERY_LOG_VIEW,
    STAFF_VIEW,
    STAFF_MANAGE,
    COMPLAINT_MANAGE,
    NOTICE_MANAGE,
    NOTICE_READ,
    REPORT_VIEW,
    AUDIT_VIEW,
    // Resident
    FLAT_VIEW_OWN,
    RESIDENT_REQUEST,
    FAMILY_MANAGE,
    VISITOR_INVITE,
    VISITOR_DECIDE,
    VISITOR_VIEW_OWN,
    DELIVERY_DECIDE,
    DELIVERY_VIEW_OWN,
    STAFF_VIEW_OWN,
    COMPLAINT_CREATE,
    COMPLAINT_VIEW_OWN,
    // Guard
    GATE_VISITOR_OPERATE,
    GATE_DELIVERY_OPERATE,
    GATE_STAFF_OPERATE,
    GATE_LOG_VIEW
}
