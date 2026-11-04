package com.mygateapp.society.controller;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.Permission;
import com.mygateapp.security.Role;
import com.mygateapp.society.dto.SocietyDtos;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.society.repository.SocietyMembershipRepository;
import com.mygateapp.society.repository.SocietyRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "Platform", description = "Platform-level statistics and configuration (super admin)")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformController {

    private final SocietyRepository societies;
    private final SocietyMembershipRepository memberships;
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public PlatformController(SocietyRepository societies, SocietyMembershipRepository memberships, JdbcTemplate jdbc,
                              AccessService access, AuditService audit, Clock clock) {
        this.societies = societies;
        this.memberships = memberships;
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    @Operation(summary = "Society and member counts across the platform")
    public SocietyDtos.PlatformStatsResponse stats() {
        access.requirePlatform(Permission.PLATFORM_STATS_VIEW);
        long active = societies.countByStatus(Society.Status.ACTIVE);
        long inactive = societies.countByStatus(Society.Status.INACTIVE);
        Long totalUsers = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return new SocietyDtos.PlatformStatsResponse(active + inactive, active, inactive,
                memberships.countByRoleAndStatus(Role.RESIDENT, SocietyMembership.Status.ACTIVE),
                memberships.countByRoleAndStatus(Role.GUARD, SocietyMembership.Status.ACTIVE),
                memberships.countByRoleAndStatus(Role.SOCIETY_ADMIN, SocietyMembership.Status.ACTIVE),
                totalUsers == null ? 0 : totalUsers);
    }

    @GetMapping("/settings")
    @Transactional(readOnly = true)
    public List<SocietyDtos.PlatformSettingDto> settings() {
        access.requirePlatform(Permission.PLATFORM_SETTINGS_MANAGE);
        return jdbc.query("SELECT setting_key, setting_value, description, updated_at FROM platform_settings ORDER BY setting_key",
                (rs, i) -> new SocietyDtos.PlatformSettingDto(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()));
    }

    @PutMapping("/settings")
    @Transactional
    @Operation(summary = "Create or update platform settings (key -> value, max 1000 characters per value)")
    public List<SocietyDtos.PlatformSettingDto> updateSettings(@RequestBody Map<String, String> values) {
        access.requirePlatform(Permission.PLATFORM_SETTINGS_MANAGE);
        values.forEach((key, value) -> {
            if (key == null || !key.matches("^[a-z0-9.-]{3,80}$") || value == null || value.length() > 1000) {
                throw com.mygateapp.exception.ApiException.field(key == null ? "key" : key,
                        "Keys use lowercase letters, digits, dots and hyphens; values are at most 1000 characters");
            }
            jdbc.update("""
                    INSERT INTO platform_settings (setting_key, setting_value, updated_at, updated_by) VALUES (?, ?, ?, ?)
                    ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value,
                        updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
                    """, key, value, Timestamp.from(clock.instant()), access.currentUserId());
        });
        audit.record(null, "PLATFORM_SETTINGS_UPDATED", "PLATFORM", null, "Platform settings updated",
                Map.of("keys", values.keySet()));
        return settings();
    }
}
