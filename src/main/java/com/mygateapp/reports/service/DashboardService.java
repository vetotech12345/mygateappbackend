package com.mygateapp.reports.service;

import com.mygateapp.activity.service.ActivityService;
import com.mygateapp.common.TimeRanges;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.deliveries.entity.Delivery;
import com.mygateapp.deliveries.service.DeliveryService;
import com.mygateapp.notices.service.NoticeService;
import com.mygateapp.reports.dto.DashboardDtos;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.Permission;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.service.SocietyService;
import com.mygateapp.visitors.enums.VisitorEnums;
import com.mygateapp.visitors.service.VisitorService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dashboards for the resident app, the guard app and the admin panel. "Today" uses the society's time zone. */
@Service
public class DashboardService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final SocietyService societyService;
    private final VisitorService visitorService;
    private final DeliveryService deliveryService;
    private final NoticeService noticeService;
    private final ActivityService activityService;
    private final Clock clock;

    public DashboardService(NamedParameterJdbcTemplate jdbc, AccessService access, SocietyService societyService,
                            VisitorService visitorService, DeliveryService deliveryService, NoticeService noticeService,
                            ActivityService activityService, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.societyService = societyService;
        this.visitorService = visitorService;
        this.deliveryService = deliveryService;
        this.noticeService = noticeService;
        this.activityService = activityService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardDtos.ResidentDashboard resident() {
        UUID societyId = access.currentSocietyId();
        access.require(societyId, Permission.VISITOR_VIEW_OWN);
        Set<UUID> flats = access.access().flatIdsIn(societyId);
        if (flats.isEmpty()) {
            return new DashboardDtos.ResidentDashboard(List.of(), List.of(), 0, 0, 0, 0, 0, 0,
                    noticeService.latest(societyId, 3), List.of());
        }
        TimeRanges.Range today = today(societyId);
        MapSqlParameterSource p = params(societyId, today).addValue("flats", flats);
        return new DashboardDtos.ResidentDashboard(
                visitorService.list(new VisitorService.ListQuery(VisitorEnums.Scope.PENDING, null, null, null, null, null, 0, 20)).content(),
                deliveryService.list(Delivery.Scope.PENDING, null, null, null, 0, 20).content(),
                count("""
                        SELECT COUNT(*) FROM visitors WHERE flat_id IN (:flats)
                          AND ((entry_at >= :start AND entry_at < :end)
                               OR (source = 'WALK_IN' AND requested_at >= :start AND requested_at < :end))
                        """, p),
                count("SELECT COUNT(*) FROM visitors WHERE flat_id IN (:flats) AND movement_status = 'INSIDE'", p),
                count("SELECT COUNT(*) FROM deliveries WHERE flat_id IN (:flats) AND requested_at >= :start AND requested_at < :end", p),
                count("""
                        SELECT COUNT(DISTINCT s.id) FROM domestic_staff s
                        JOIN domestic_staff_flat_assignments a ON a.staff_id = s.id AND a.active
                        WHERE a.flat_id IN (:flats) AND s.inside
                        """, p),
                count("SELECT COUNT(*) FROM complaints WHERE flat_id IN (:flats) AND status IN ('OPEN', 'IN_PROGRESS')", p),
                noticeService.unreadCount(societyId),
                noticeService.latest(societyId, 3),
                activityService.feed(societyId, flats, true, null, 0, 6).content());
    }

    @Transactional(readOnly = true)
    public DashboardDtos.GuardDashboard guard() {
        UUID societyId = access.currentSocietyId();
        access.requireGuard(societyId);
        TimeRanges.Range today = today(societyId);
        MapSqlParameterSource p = params(societyId, today);
        return new DashboardDtos.GuardDashboard(
                count("""
                        SELECT COUNT(*) FROM visitors WHERE society_id = :societyId
                          AND ((entry_at >= :start AND entry_at < :end)
                               OR (source = 'WALK_IN' AND requested_at >= :start AND requested_at < :end))
                        """, p),
                count("SELECT COUNT(*) FROM visitors WHERE society_id = :societyId AND approval_status = 'PENDING'", p),
                count("""
                        SELECT COUNT(*) FROM visitors WHERE society_id = :societyId AND approval_status = 'APPROVED'
                          AND movement_status = 'NOT_ENTERED'
                          AND (source = 'WALK_IN' OR (valid_from <= :now AND valid_until >= :now))
                        """, p),
                count("SELECT COUNT(*) FROM visitors WHERE society_id = :societyId AND movement_status = 'INSIDE'", p),
                count("SELECT COUNT(*) FROM deliveries WHERE society_id = :societyId AND requested_at >= :start AND requested_at < :end", p),
                count("SELECT COUNT(*) FROM domestic_staff WHERE society_id = :societyId AND inside", p),
                activityService.feed(societyId, null, false, null, 0, 8).content());
    }

    @Transactional(readOnly = true)
    public PageResponse<ActivityService.ActivityItem> residentActivity(ActivityService.Kind kind, Integer page, Integer size) {
        UUID societyId = access.currentSocietyId();
        access.require(societyId, Permission.VISITOR_VIEW_OWN);
        Set<UUID> flats = access.access().flatIdsIn(societyId);
        if (flats.isEmpty()) {
            return PageResponse.of(List.of(), 0, 20, 0);
        }
        return activityService.feed(societyId, flats, true, kind, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<ActivityService.ActivityItem> guardActivity(ActivityService.Kind kind, Integer page, Integer size) {
        UUID societyId = access.currentSocietyId();
        access.require(societyId, Permission.GATE_LOG_VIEW);
        return activityService.feed(societyId, null, false, kind == ActivityService.Kind.COMPLAINT ? null : kind, page, size);
    }

    @Transactional(readOnly = true)
    public DashboardDtos.AdminDashboard admin(UUID societyIdParam) {
        UUID societyId = access.resolveSocietyId(societyIdParam);
        access.require(societyId, Permission.REPORT_VIEW);
        Society society = societyService.requireSociety(societyId);
        TimeRanges.Range today = TimeRanges.today(clock, society.zoneId());
        MapSqlParameterSource p = params(societyId, today);
        return new DashboardDtos.AdminDashboard(societyId, LocalDate.now(clock.withZone(society.zoneId())),
                count("""
                        SELECT COUNT(*) FROM visitors WHERE society_id = :societyId
                          AND ((entry_at >= :start AND entry_at < :end)
                               OR (source = 'WALK_IN' AND requested_at >= :start AND requested_at < :end))
                        """, p),
                count("SELECT COUNT(*) FROM visitors WHERE society_id = :societyId AND approval_status = 'PENDING'", p),
                count("SELECT COUNT(*) FROM visitors WHERE society_id = :societyId AND movement_status = 'INSIDE'", p),
                count("SELECT COUNT(*) FROM deliveries WHERE society_id = :societyId AND requested_at >= :start AND requested_at < :end", p),
                count("SELECT COUNT(*) FROM deliveries WHERE society_id = :societyId AND approval_status = 'PENDING'", p),
                count("SELECT COUNT(*) FROM complaints WHERE society_id = :societyId AND status = 'OPEN'", p),
                count("SELECT COUNT(*) FROM complaints WHERE society_id = :societyId AND status = 'IN_PROGRESS'", p),
                count("SELECT COUNT(*) FROM flat_memberships WHERE society_id = :societyId AND status = 'PENDING'", p),
                count("""
                        SELECT COUNT(*) FROM society_memberships
                        WHERE society_id = :societyId AND role_code = 'RESIDENT' AND status = 'ACTIVE'
                        """, p),
                count("SELECT COUNT(*) FROM security_guards WHERE society_id = :societyId AND status = 'ACTIVE'", p),
                count("SELECT COUNT(*) FROM security_guards WHERE society_id = :societyId AND status = 'ACTIVE' AND on_duty", p),
                count("SELECT COUNT(*) FROM domestic_staff WHERE society_id = :societyId AND inside", p),
                count("SELECT COUNT(*) FROM flats WHERE society_id = :societyId AND status = 'ACTIVE'", p),
                count("""
                        SELECT COUNT(DISTINCT flat_id) FROM flat_memberships
                        WHERE society_id = :societyId AND status = 'ACTIVE'
                        """, p),
                clock.instant());
    }

    private TimeRanges.Range today(UUID societyId) {
        return TimeRanges.today(clock, societyService.requireSociety(societyId).zoneId());
    }

    private MapSqlParameterSource params(UUID societyId, TimeRanges.Range today) {
        Instant now = clock.instant();
        return new MapSqlParameterSource()
                .addValue("societyId", societyId)
                .addValue("start", Timestamp.from(today.from()))
                .addValue("end", Timestamp.from(today.to()))
                .addValue("now", Timestamp.from(now));
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }
}
