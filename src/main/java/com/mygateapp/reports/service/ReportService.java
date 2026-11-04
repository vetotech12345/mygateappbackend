package com.mygateapp.reports.service;

import com.mygateapp.common.TimeRanges;
import com.mygateapp.reports.dto.DashboardDtos;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.Permission;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.service.SocietyService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Date-range reports for the admin panel. Ranges are local dates in the society's time zone (inclusive), at most
 * 366 days; the default is the last 7 days. Every report is scoped to one society the caller administers.
 */
@Service
public class ReportService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final SocietyService societyService;
    private final Clock clock;

    public ReportService(NamedParameterJdbcTemplate jdbc, AccessService access, SocietyService societyService, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.societyService = societyService;
        this.clock = clock;
    }

    private record Scope(UUID societyId, ZoneId zone, LocalDate from, LocalDate to, MapSqlParameterSource params) {
    }

    private Scope scope(UUID societyIdParam, LocalDate from, LocalDate to) {
        UUID societyId = access.resolveSocietyId(societyIdParam);
        access.require(societyId, Permission.REPORT_VIEW);
        Society society = societyService.requireSociety(societyId);
        ZoneId zone = society.zoneId();
        TimeRanges.Range range = TimeRanges.reportRange(from, to, clock, zone);
        LocalDate start = range.from().atZone(zone).toLocalDate();
        LocalDate end = range.to().atZone(zone).toLocalDate().minusDays(1);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("societyId", societyId)
                .addValue("from", Timestamp.from(range.from()))
                .addValue("to", Timestamp.from(range.to()))
                .addValue("zone", zone.getId());
        return new Scope(societyId, zone, start, end, params);
    }

    @Transactional(readOnly = true)
    public DashboardDtos.VisitorReport visitors(UUID societyId, LocalDate from, LocalDate to) {
        Scope s = scope(societyId, from, to);
        String where = "FROM visitors WHERE society_id = :societyId AND requested_at >= :from AND requested_at < :to";
        Map<LocalDate, Map<String, Long>> daily = new HashMap<>();
        jdbc.query("""
                SELECT (requested_at AT TIME ZONE :zone)::date AS day, COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE entry_at IS NOT NULL) AS entered,
                       COUNT(*) FILTER (WHERE approval_status = 'REJECTED') AS rejected,
                       COUNT(*) FILTER (WHERE source = 'INVITATION') AS invited
                """ + where + " GROUP BY day", s.params(), rs -> {
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("total", rs.getLong("total"));
            values.put("entered", rs.getLong("entered"));
            values.put("rejected", rs.getLong("rejected"));
            values.put("invited", rs.getLong("invited"));
            daily.put(rs.getDate("day").toLocalDate(), values);
        });
        List<DashboardDtos.DailyCount> days = fill(s, daily, "entered", "rejected", "invited");
        return new DashboardDtos.VisitorReport(s.from(), s.to(), sum(days, null), sum(days, "entered"), sum(days, "rejected"),
                sum(days, "invited"), grouped("SELECT approval_status, COUNT(*) " + where + " GROUP BY approval_status", s),
                grouped("SELECT visitor_type, COUNT(*) " + where + " GROUP BY visitor_type", s), days);
    }

    @Transactional(readOnly = true)
    public DashboardDtos.DeliveryReport deliveries(UUID societyId, LocalDate from, LocalDate to) {
        Scope s = scope(societyId, from, to);
        String where = "FROM deliveries WHERE society_id = :societyId AND requested_at >= :from AND requested_at < :to";
        Map<LocalDate, Map<String, Long>> daily = new HashMap<>();
        jdbc.query("""
                SELECT (requested_at AT TIME ZONE :zone)::date AS day, COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE entry_at IS NOT NULL) AS entered,
                       COUNT(*) FILTER (WHERE approval_status = 'REJECTED') AS rejected,
                       COUNT(*) FILTER (WHERE approval_status = 'LEFT_AT_GATE') AS left_at_gate
                """ + where + " GROUP BY day", s.params(), rs -> {
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("total", rs.getLong("total"));
            values.put("entered", rs.getLong("entered"));
            values.put("rejected", rs.getLong("rejected"));
            values.put("leftAtGate", rs.getLong("left_at_gate"));
            daily.put(rs.getDate("day").toLocalDate(), values);
        });
        List<DashboardDtos.DailyCount> days = fill(s, daily, "entered", "rejected", "leftAtGate");
        return new DashboardDtos.DeliveryReport(s.from(), s.to(), sum(days, null), sum(days, "entered"),
                sum(days, "rejected"), sum(days, "leftAtGate"),
                grouped("SELECT category, COUNT(*) " + where + " GROUP BY category", s),
                grouped("SELECT approval_status, COUNT(*) " + where + " GROUP BY approval_status", s), days);
    }

    @Transactional(readOnly = true)
    public DashboardDtos.ComplaintReport complaints(UUID societyId, LocalDate from, LocalDate to) {
        Scope s = scope(societyId, from, to);
        String where = "FROM complaints WHERE society_id = :societyId AND created_at >= :from AND created_at < :to";
        Map<LocalDate, Map<String, Long>> daily = new HashMap<>();
        jdbc.query("SELECT (created_at AT TIME ZONE :zone)::date AS day, COUNT(*) AS total " + where + " GROUP BY day",
                s.params(), rs -> {
                    Map<String, Long> values = new LinkedHashMap<>();
                    values.put("total", rs.getLong("total"));
                    daily.put(rs.getDate("day").toLocalDate(), values);
                });
        List<DashboardDtos.DailyCount> days = fill(s, daily);
        Long resolved = jdbc.queryForObject("""
                SELECT COUNT(*) FROM complaints
                WHERE society_id = :societyId AND resolved_at >= :from AND resolved_at < :to
                """, s.params(), Long.class);
        Double avgHours = jdbc.queryForObject("""
                SELECT AVG(EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600.0) FROM complaints
                WHERE society_id = :societyId AND resolved_at >= :from AND resolved_at < :to
                """, s.params(), Double.class);
        Long open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM complaints WHERE society_id = :societyId AND status IN ('OPEN', 'IN_PROGRESS')
                """, s.params(), Long.class);
        return new DashboardDtos.ComplaintReport(s.from(), s.to(), sum(days, null), resolved == null ? 0 : resolved,
                avgHours == null ? null : Math.round(avgHours * 10.0) / 10.0, open == null ? 0 : open,
                grouped("SELECT status, COUNT(*) " + where + " GROUP BY status", s),
                grouped("SELECT category, COUNT(*) " + where + " GROUP BY category", s), days);
    }

    private Map<String, Long> grouped(String sql, Scope s) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql + " ORDER BY 2 DESC", s.params(), rs -> {
            result.put(rs.getString(1), rs.getLong(2));
        });
        return result;
    }

    /** One entry per day of the range (zero-filled), so charts need no client-side gap handling. */
    private static List<DashboardDtos.DailyCount> fill(Scope s, Map<LocalDate, Map<String, Long>> daily, String... keys) {
        List<DashboardDtos.DailyCount> result = new ArrayList<>();
        for (LocalDate day = s.from(); !day.isAfter(s.to()); day = day.plusDays(1)) {
            Map<String, Long> values = daily.getOrDefault(day, Map.of());
            Map<String, Long> breakdown = new LinkedHashMap<>();
            for (String key : keys) {
                breakdown.put(key, values.getOrDefault(key, 0L));
            }
            result.add(new DashboardDtos.DailyCount(day, values.getOrDefault("total", 0L), breakdown));
        }
        return result;
    }

    private static long sum(List<DashboardDtos.DailyCount> days, String key) {
        return days.stream().mapToLong(d -> key == null ? d.total() : d.breakdown().getOrDefault(key, 0L)).sum();
    }
}
