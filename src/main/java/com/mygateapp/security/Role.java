package com.mygateapp.security;

/** Application roles. SUPER_ADMIN is platform-wide; the others are held per society (society_memberships). */
public enum Role {
    SUPER_ADMIN,
    SOCIETY_ADMIN,
    RESIDENT,
    GUARD;

    public String authority() {
        return "ROLE_" + name();
    }
}
