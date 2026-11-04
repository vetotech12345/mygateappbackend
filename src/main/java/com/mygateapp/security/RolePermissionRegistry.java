package com.mygateapp.security;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** In-memory copy of {@code role_permissions}, loaded lazily on first use and reloadable. */
@Component
public class RolePermissionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionRegistry.class);

    private final JdbcTemplate jdbc;
    private volatile Map<Role, Set<Permission>> grants;

    public RolePermissionRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean has(Role role, Permission permission) {
        return grants().getOrDefault(role, Set.of()).contains(permission);
    }

    public Set<Permission> permissionsOf(Role role) {
        return grants().getOrDefault(role, Set.of());
    }

    public synchronized void reload() {
        Map<Role, Set<Permission>> loaded = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            loaded.put(role, EnumSet.noneOf(Permission.class));
        }
        jdbc.query("SELECT role_code, permission_code FROM role_permissions", rs -> {
            String roleCode = rs.getString(1);
            String permissionCode = rs.getString(2);
            try {
                loaded.get(Role.valueOf(roleCode)).add(Permission.valueOf(permissionCode));
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring unknown role/permission grant {} -> {}", roleCode, permissionCode);
            }
        });
        grants = loaded;
    }

    private Map<Role, Set<Permission>> grants() {
        Map<Role, Set<Permission>> current = grants;
        if (current == null) {
            reload();
            current = grants;
        }
        return current;
    }
}
