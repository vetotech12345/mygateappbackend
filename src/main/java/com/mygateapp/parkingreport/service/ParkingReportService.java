package com.mygateapp.parkingreport.service;

import com.mygateapp.common.Pages;
import com.mygateapp.common.TimeRanges;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.parking.dto.ParkingRefs.Money;
import com.mygateapp.parking.entity.ParkingArea;
import com.mygateapp.parking.entity.ParkingSession;
import com.mygateapp.parking.entity.ParkingSlot;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import com.mygateapp.parking.enums.ParkingEnums.SessionStatus;
import com.mygateapp.parking.enums.ParkingEnums.SlotStatus;
import com.mygateapp.parking.repository.ParkingAreaRepository;
import com.mygateapp.parking.repository.ParkingSessionRepository;
import com.mygateapp.parking.repository.ParkingSlotRepository;
import com.mygateapp.parking.service.ParkingAccess;
import com.mygateapp.parking.service.ParkingInventoryService;
import com.mygateapp.parking.service.ParkingInventoryService.SlotState;
import com.mygateapp.parking.service.ParkingRefLoader;
import com.mygateapp.parking.service.ParkingSessionQueryService;
import com.mygateapp.parking.service.ParkingSettingsStore;
import com.mygateapp.parking.service.ParkingSupport;
import com.mygateapp.parkingreport.dto.ParkingReportDtos.AmountCount;
import com.mygateapp.parkingreport.dto.ParkingReportDtos.AreaOccupancy;
import com.mygateapp.parkingreport.dto.ParkingReportDtos.OccupancyReport;
import com.mygateapp.parkingreport.dto.ParkingReportDtos.Overview;
import com.mygateapp.parkingreport.dto.ParkingReportDtos.RevenueReport;
import com.mygateapp.parkingreport.dto.ParkingReportDtos.SessionsReport;
import com.mygateapp.parkingreport.dto.ParkingReportDtos.SlotCounts;
import com.mygateapp.parkingreport.dto.ParkingReportDtos.UsageOccupancy;
import com.mygateapp.security.Permission;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Society-scoped parking reports. Every report requires PARKING_REPORT_VIEW (administrators only). */
@Service
public class ParkingReportService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ParkingSlotRepository slots;
    private final ParkingAreaRepository areas;
    private final ParkingSessionRepository sessions;
    private final ParkingInventoryService inventory;
    private final ParkingSessionQueryService sessionQueries;
    private final ParkingSettingsStore settingsStore;
    private final ParkingAccess access;
    private final ParkingSupport support;
    private final Clock clock;

    public ParkingReportService(NamedParameterJdbcTemplate jdbc, ParkingSlotRepository slots, ParkingAreaRepository areas,
                                ParkingSessionRepository sessions, ParkingInventoryService inventory,
                                ParkingSessionQueryService sessionQueries, ParkingSettingsStore settingsStore,
                                ParkingAccess access, ParkingSupport support, Clock clock) {
        this.jdbc = jdbc;
        this.slots = slots;
        this.areas = areas;
        this.sessions = sessions;
        this.inventory = inventory;
        this.sessionQueries = sessionQueries;
        this.settingsStore = settingsStore;
        this.access = access;
        this.support = support;
        this.clock = clock;
    }

    // =============================================================== overview

    @Transactional(readOnly = true)
    public Overview overview() {
        UUID societyId = access.requireAdmin(Permission.PARKING_REPORT_VIEW);
        Instant now = clock.instant();
        ZoneId zone = support.zone(societyId);
        String currency = settingsStore.settingsFor(societyId).getCurrency();
        int grace = settingsStore.settingsFor(societyId).getFreeGraceMinutes();
        Collection<SlotState> states = inventory.statesOf(societyId, slots.findBySocietyId(societyId)).values();
        TimeRanges.Range today = TimeRanges.today(clock, zone);
        LocalDate localToday = LocalDate.now(clock.withZone(zone));
        Instant monthStart = localToday.withDayOfMonth(1).atStartOfDay(zone).toInstant();
        MapSqlParameterSource p = new MapSqlParameterSource("s", societyId)
                .addValue("now", Timestamp.from(now))
                .addValue("overstay", Timestamp.from(now.minus(Duration.ofMinutes(grace))))
                .addValue("dayStart", Timestamp.from(today.from()))
                .addValue("dayEnd", Timestamp.from(today.to()))
                .addValue("monthStart", Timestamp.from(monthStart));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM vehicles WHERE society_id = :s AND active AND registration_status = 'APPROVED') AS approved,
                  (SELECT COUNT(*) FROM vehicles WHERE society_id = :s AND active AND registration_status = 'PENDING') AS pending_vehicles,
                  (SELECT COUNT(*) FROM parking_sessions WHERE society_id = :s AND status = 'ACTIVE') AS inside,
                  (SELECT COUNT(*) FROM parking_allocations WHERE society_id = :s AND status = 'ACTIVE'
                      AND valid_from <= :now AND (valid_until IS NULL OR valid_until > :now)) AS allocations,
                  (SELECT COUNT(*) FROM parking_permits WHERE society_id = :s AND status = 'ACTIVE'
                      AND valid_from <= :now AND (valid_until IS NULL OR valid_until > :now)) AS permits,
                  (SELECT COUNT(*) FROM parking_permits WHERE society_id = :s AND status = 'EXPIRED') AS expired_permits,
                  (SELECT COUNT(*) FROM parking_requests WHERE society_id = :s AND status = 'PENDING') AS pending_requests,
                  (SELECT COUNT(*) FROM parking_sessions WHERE society_id = :s AND status = 'ACTIVE'
                      AND valid_until IS NOT NULL AND valid_until < :overstay) AS overstays,
                  (SELECT COUNT(*) FROM parking_sessions WHERE society_id = :s AND entry_at >= :dayStart AND entry_at < :dayEnd) AS entries,
                  (SELECT COUNT(*) FROM parking_sessions WHERE society_id = :s AND exit_at >= :dayStart AND exit_at < :dayEnd) AS exits,
                  (SELECT COUNT(*) FROM parking_payments WHERE society_id = :s AND status = 'PENDING') AS pending_payments,
                  (SELECT COALESCE(SUM(amount), 0) FROM parking_payments WHERE society_id = :s AND status = 'PENDING') AS pending_amount,
                  (SELECT COALESCE(SUM(amount), 0) FROM parking_payments WHERE society_id = :s AND status = 'PAID'
                      AND paid_at >= :dayStart AND paid_at < :dayEnd) AS revenue_today,
                  (SELECT COALESCE(SUM(amount), 0) FROM parking_payments WHERE society_id = :s AND status = 'PAID'
                      AND paid_at >= :monthStart) AS revenue_month
                """, p);
        return new Overview(now, counts(states), num(row, "approved"), num(row, "pending_vehicles"), num(row, "inside"),
                num(row, "allocations"), num(row, "permits"), num(row, "expired_permits"), num(row, "pending_requests"),
                num(row, "overstays"), num(row, "entries"), num(row, "exits"), num(row, "pending_payments"),
                money(row, "pending_amount", currency), money(row, "revenue_today", currency),
                money(row, "revenue_month", currency));
    }

    // ============================================================== occupancy

    @Transactional(readOnly = true)
    public OccupancyReport occupancy() {
        UUID societyId = access.requireAdmin(Permission.PARKING_REPORT_VIEW);
        List<ParkingSlot> all = slots.findBySocietyId(societyId);
        Map<UUID, SlotState> states = inventory.statesOf(societyId, all);
        List<AreaOccupancy> byArea = new ArrayList<>();
        for (ParkingArea area : areas.findBySocietyIdOrderByName(societyId)) {
            List<SlotState> inArea = states.values().stream().filter(s -> s.slot().getAreaId().equals(area.getId())).toList();
            SlotCounts counts = counts(inArea);
            double percent = counts.inService() == 0 ? 0 : Math.round(counts.occupied() * 1000.0 / counts.inService()) / 10.0;
            byArea.add(new AreaOccupancy(ParkingRefLoader.areaRef(area), area.getCode(), area.getUsage(), area.isActive(),
                    counts, percent));
        }
        Map<ParkingUsage, List<SlotState>> byUsage = new EnumMap<>(ParkingUsage.class);
        states.values().forEach(s -> byUsage.computeIfAbsent(s.slot().getUsage(), k -> new ArrayList<>()).add(s));
        List<UsageOccupancy> usages = byUsage.entrySet().stream()
                .map(e -> new UsageOccupancy(e.getKey(), counts(e.getValue()))).toList();
        return new OccupancyReport(clock.instant(), counts(states.values()), byArea, usages);
    }

    // =============================================================== sessions

    @Transactional(readOnly = true)
    public SessionsReport sessions(LocalDate from, LocalDate to, Category category, SessionStatus status, Integer page,
                                   Integer size) {
        UUID societyId = access.requireAdmin(Permission.PARKING_REPORT_VIEW);
        ZoneId zone = support.zone(societyId);
        TimeRanges.Range range = TimeRanges.reportRange(from, to, clock, zone);
        MapSqlParameterSource p = new MapSqlParameterSource("s", societyId)
                .addValue("from", Timestamp.from(range.from())).addValue("to", Timestamp.from(range.to()))
                .addValue("now", Timestamp.from(clock.instant()));
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            byCategory.put(c.name(), 0L);
        }
        jdbc.query("""
                SELECT category, COUNT(*) FROM parking_sessions
                WHERE society_id = :s AND entry_at >= :from AND entry_at < :to GROUP BY category
                """, p, rs -> {
            byCategory.put(rs.getString(1), rs.getLong(2));
        });
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE rate_snapshot IS NOT NULL) AS paid,
                       COUNT(*) FILTER (WHERE emergency_override) AS overrides,
                       COUNT(*) FILTER (WHERE valid_until IS NOT NULL AND COALESCE(exit_at, :now) > valid_until) AS overstays,
                       COALESCE(AVG(EXTRACT(EPOCH FROM (exit_at - entry_at)) / 60) FILTER (WHERE exit_at IS NOT NULL), 0) AS avg_minutes
                FROM parking_sessions WHERE society_id = :s AND entry_at >= :from AND entry_at < :to
                """, p);
        Specification<ParkingSession> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("societyId"), societyId));
            predicates.add(cb.greaterThanOrEqualTo(root.get("entryAt"), range.from()));
            predicates.add(cb.lessThan(root.get("entryAt"), range.to()));
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<ParkingSession> result = sessions.findAll(spec, Pages.of(page, size, Sort.by(Sort.Direction.DESC, "entryAt")));
        ParkingSessionQueryService.Mapper mapper = sessionQueries.mapper(societyId, result.getContent());
        LocalDate fromDate = range.from().atZone(zone).toLocalDate();
        LocalDate toDate = range.to().atZone(zone).toLocalDate().minusDays(1);
        return new SessionsReport(fromDate, toDate, num(totals, "total"), byCategory, num(totals, "paid"),
                num(totals, "overrides"), num(totals, "overstays"), Math.round(((Number) totals.get("avg_minutes")).doubleValue()),
                PageResponse.of(result, s -> mapper.map(s, false)));
    }

    // ================================================================ revenue

    @Transactional(readOnly = true)
    public RevenueReport revenue(LocalDate from, LocalDate to) {
        UUID societyId = access.requireAdmin(Permission.PARKING_REPORT_VIEW);
        ZoneId zone = support.zone(societyId);
        String currency = settingsStore.settingsFor(societyId).getCurrency();
        TimeRanges.Range range = TimeRanges.reportRange(from, to, clock, zone);
        MapSqlParameterSource p = new MapSqlParameterSource("s", societyId)
                .addValue("from", Timestamp.from(range.from())).addValue("to", Timestamp.from(range.to()))
                .addValue("tz", zone.getId());
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE status IN ('PAID', 'REFUNDED') AND paid_at >= :from AND paid_at < :to), 0) AS collected,
                       COUNT(*) FILTER (WHERE status IN ('PAID', 'REFUNDED') AND paid_at >= :from AND paid_at < :to) AS collected_count,
                       COALESCE(SUM(amount) FILTER (WHERE status = 'REFUNDED' AND refunded_at >= :from AND refunded_at < :to), 0) AS refunded,
                       COALESCE(SUM(amount) FILTER (WHERE status = 'WAIVED' AND paid_at >= :from AND paid_at < :to), 0) AS waived,
                       COALESCE(SUM(amount) FILTER (WHERE status = 'PENDING'), 0) AS pending,
                       COUNT(*) FILTER (WHERE status = 'PENDING') AS pending_count
                FROM parking_payments WHERE society_id = :s
                """, p);
        BigDecimal collected = (BigDecimal) totals.get("collected");
        BigDecimal refunded = (BigDecimal) totals.get("refunded");
        List<AmountCount> byMethod = grouped("method", p, currency);
        List<AmountCount> byPurpose = grouped("purpose", p, currency);
        List<AmountCount> daily = series("YYYY-MM-DD", "day", p, currency);
        List<AmountCount> monthly = series("YYYY-MM", "month", p, currency);
        return new RevenueReport(range.from().atZone(zone).toLocalDate(), range.to().atZone(zone).toLocalDate().minusDays(1),
                currency, Money.of(collected, currency), num(totals, "collected_count"), Money.of(refunded, currency),
                money(totals, "waived", currency), Money.of(collected.subtract(refunded), currency),
                money(totals, "pending", currency), num(totals, "pending_count"), byMethod, byPurpose, daily, monthly);
    }

    private List<AmountCount> grouped(String column, MapSqlParameterSource p, String currency) {
        return jdbc.query("SELECT " + column + ", COALESCE(SUM(amount), 0), COUNT(*) FROM parking_payments "
                        + "WHERE society_id = :s AND status IN ('PAID', 'REFUNDED') AND paid_at >= :from AND paid_at < :to "
                        + "GROUP BY " + column + " ORDER BY 2 DESC", p,
                (rs, i) -> new AmountCount(rs.getString(1), Money.of(rs.getBigDecimal(2), currency), rs.getLong(3)));
    }

    /** Daily or monthly collections in the society's time zone ({@code unit} is a fixed literal, never user input). */
    private List<AmountCount> series(String format, String unit, MapSqlParameterSource p, String currency) {
        return jdbc.query("SELECT to_char(date_trunc('" + unit + "', paid_at AT TIME ZONE :tz), '" + format + "') AS bucket, "
                        + "COALESCE(SUM(amount), 0), COUNT(*) FROM parking_payments "
                        + "WHERE society_id = :s AND status IN ('PAID', 'REFUNDED') AND paid_at >= :from AND paid_at < :to "
                        + "GROUP BY bucket ORDER BY bucket", p,
                (rs, i) -> new AmountCount(rs.getString(1), Money.of(rs.getBigDecimal(2), currency), rs.getLong(3)));
    }

    // ================================================================ helpers

    private static SlotCounts counts(Collection<SlotState> states) {
        int total = states.size();
        int outOfService = (int) states.stream().filter(SlotState::blocked).count();
        int allocated = (int) states.stream().filter(SlotState::allocated).count();
        int occupied = (int) states.stream().filter(SlotState::occupied).count();
        int reserved = (int) states.stream().filter(SlotState::reserved).count();
        int available = (int) states.stream().filter(s -> s.status() == SlotStatus.AVAILABLE).count();
        return new SlotCounts(total, total - outOfService, outOfService, allocated, total - allocated, occupied,
                total - occupied, reserved, available);
    }

    private static long num(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static Money money(Map<String, Object> row, String key, String currency) {
        Object value = row.get(key);
        return Money.of(value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()), currency);
    }
}
