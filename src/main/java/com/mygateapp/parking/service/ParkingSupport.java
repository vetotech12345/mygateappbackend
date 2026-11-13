package com.mygateapp.parking.service;

import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.notifications.enums.NotificationType;
import com.mygateapp.notifications.service.NotificationService;
import com.mygateapp.residents.repository.FlatMembershipRepository;
import com.mygateapp.security.Role;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.society.repository.SocietyMembershipRepository;
import com.mygateapp.society.repository.SocietyRepository;
import com.mygateapp.guards.repository.GuardRepository;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.repository.UserRepository;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared helpers of the parking services: per-society reference numbers, actor lookup and PARKING_UPDATE
 * notifications. Notifications never contain amounts, mobile numbers or payment references.
 */
@Component
public class ParkingSupport {

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final FlatMembershipRepository flatMemberships;
    private final GuardRepository guards;
    private final SocietyMembershipRepository memberships;
    private final SocietyRepository societies;
    private final UserRepository users;

    public ParkingSupport(JdbcTemplate jdbc, NotificationService notifications, FlatMembershipRepository flatMemberships,
                          GuardRepository guards, SocietyMembershipRepository memberships, SocietyRepository societies,
                          UserRepository users) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.flatMemberships = flatMemberships;
        this.guards = guards;
        this.memberships = memberships;
        this.societies = societies;
        this.users = users;
    }

    // ----------------------------------------------------------- numbering

    /** Atomic per-society sequence starting at 1001 (same mechanism as complaint tickets). */
    public long nextNumber(UUID societyId, String counter) {
        Long value = jdbc.queryForObject("""
                INSERT INTO society_counters (society_id, counter_name, current_value) VALUES (?, ?, 1001)
                ON CONFLICT (society_id, counter_name) DO UPDATE SET current_value = society_counters.current_value + 1
                RETURNING current_value
                """, Long.class, societyId, counter);
        return value == null ? 1001 : value;
    }

    public String requestNumber(UUID societyId) {
        return "PR-" + nextNumber(societyId, "PARKING_REQUEST");
    }

    /** e.g. GREENWOOD-RES-1001. */
    public String permitNumber(UUID societyId, String typeCode) {
        return societyCode(societyId) + "-" + typeCode + "-" + nextNumber(societyId, "PARKING_PERMIT");
    }

    /** e.g. GREENWOOD-RC-2026-1001. */
    public String receiptNumber(UUID societyId, int year) {
        return societyCode(societyId) + "-RC-" + year + "-" + nextNumber(societyId, "PARKING_RECEIPT");
    }

    private String societyCode(UUID societyId) {
        String code = society(societyId).getCode();
        return code.length() > 12 ? code.substring(0, 12) : code;
    }

    public Society society(UUID societyId) {
        return societies.findById(societyId).orElseThrow(() -> new ApiException(ErrorCode.SOCIETY_NOT_FOUND));
    }

    public ZoneId zone(UUID societyId) {
        return society(societyId).zoneId();
    }

    public User user(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    public String displayName(UUID userId) {
        return userId == null ? null : users.findById(userId).map(User::displayName).orElse(null);
    }

    // -------------------------------------------------------- notifications

    public void notifyFlat(UUID flatId, UUID societyId, String title, String body, Notification.ReferenceType type,
                           UUID referenceId) {
        if (flatId == null) {
            return;
        }
        notify(flatMemberships.findActiveResidentUserIds(List.of(flatId)), societyId, title, body, type, referenceId);
    }

    public void notifyGuards(UUID societyId, String title, String body, Notification.ReferenceType type,
                             UUID referenceId) {
        notify(guards.findOnDutyGuardUserIds(societyId, null), societyId, title, body, type, referenceId);
    }

    public void notifyAdmins(UUID societyId, String title, String body, Notification.ReferenceType type,
                             UUID referenceId) {
        List<UUID> admins = memberships.findBySocietyIdAndRoleAndStatus(societyId, Role.SOCIETY_ADMIN,
                SocietyMembership.Status.ACTIVE).stream().map(SocietyMembership::getUserId).toList();
        notify(admins, societyId, title, body, type, referenceId);
    }

    public void notify(Collection<UUID> userIds, UUID societyId, String title, String body,
                       Notification.ReferenceType type, UUID referenceId) {
        notifications.notifyUsers(userIds, societyId, NotificationType.PARKING_UPDATE, title, body, type, referenceId);
    }
}
