package com.mygateapp.activity.service;

import com.mygateapp.common.Pages;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.complaints.entity.Complaint;
import com.mygateapp.complaints.repository.ComplaintRepository;
import com.mygateapp.deliveries.entity.Delivery;
import com.mygateapp.deliveries.repository.DeliveryRepository;
import com.mygateapp.domesticstaff.entity.StaffAttendance;
import com.mygateapp.domesticstaff.repository.StaffAttendanceRepository;
import com.mygateapp.visitors.entity.Visitor;
import com.mygateapp.visitors.repository.VisitorRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unified activity feed (visitors, deliveries, staff attendance, complaints) for the resident and guard apps.
 * One paginated UNION query decides order; the referenced rows are then loaded in batches to build the texts.
 * Callers are responsible for authorization and pass the society and (for residents) flat scope.
 */
@Service
public class ActivityService {

    public enum Kind { VISITOR, DELIVERY, STAFF, COMPLAINT }

    /** Matches the Flutter {@code ActivityItem}. */
    public record ActivityItem(String id, Kind kind, String title, String subtitle, Instant at, UUID referenceId, String status) {
    }

    /** The feed only looks back this far, which keeps the union bounded for large societies. */
    static final Duration LOOKBACK = Duration.ofDays(180);

    private final NamedParameterJdbcTemplate jdbc;
    private final VisitorRepository visitors;
    private final DeliveryRepository deliveries;
    private final StaffAttendanceRepository attendance;
    private final ComplaintRepository complaints;
    private final Clock clock;

    public ActivityService(NamedParameterJdbcTemplate jdbc, VisitorRepository visitors, DeliveryRepository deliveries,
                           StaffAttendanceRepository attendance, ComplaintRepository complaints, Clock clock) {
        this.jdbc = jdbc;
        this.visitors = visitors;
        this.deliveries = deliveries;
        this.attendance = attendance;
        this.complaints = complaints;
        this.clock = clock;
    }

    /**
     * @param flatIds null for the whole society (guards); otherwise the resident's flats (must not be empty)
     */
    @Transactional(readOnly = true)
    public PageResponse<ActivityItem> feed(UUID societyId, Collection<UUID> flatIds, boolean includeComplaints, Kind kind,
                                           Integer page, Integer size) {
        var pageable = Pages.of(page, size);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("societyId", societyId)
                .addValue("since", Timestamp.from(clock.instant().minus(LOOKBACK)))
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        boolean scoped = flatIds != null;
        if (scoped) {
            params.addValue("flatIds", flatIds);
        }
        List<String> parts = new ArrayList<>();
        if (kind == null || kind == Kind.VISITOR) {
            parts.add("SELECT v.id AS id, 'VISITOR' AS kind, COALESCE(v.exit_at, v.entry_at, v.decided_at, v.requested_at) AS at "
                    + "FROM visitors v WHERE v.society_id = :societyId AND v.requested_at >= :since"
                    + (scoped ? " AND v.flat_id IN (:flatIds)" : ""));
        }
        if (kind == null || kind == Kind.DELIVERY) {
            parts.add("SELECT d.id AS id, 'DELIVERY' AS kind, COALESCE(d.exit_at, d.entry_at, d.decided_at, d.requested_at) AS at "
                    + "FROM deliveries d WHERE d.society_id = :societyId AND d.requested_at >= :since"
                    + (scoped ? " AND d.flat_id IN (:flatIds)" : ""));
        }
        if (kind == null || kind == Kind.STAFF) {
            parts.add("SELECT a.id AS id, 'STAFF' AS kind, COALESCE(a.check_out_at, a.check_in_at) AS at "
                    + "FROM domestic_staff_attendance a WHERE a.society_id = :societyId AND a.check_in_at >= :since"
                    + (scoped ? " AND EXISTS (SELECT 1 FROM domestic_staff_flat_assignments x "
                    + "WHERE x.staff_id = a.staff_id AND x.active AND x.flat_id IN (:flatIds))" : ""));
        }
        if (includeComplaints && (kind == null || kind == Kind.COMPLAINT)) {
            parts.add("SELECT c.id AS id, 'COMPLAINT' AS kind, c.updated_at AS at FROM complaints c "
                    + "WHERE c.society_id = :societyId AND c.updated_at >= :since"
                    + (scoped ? " AND c.flat_id IN (:flatIds)" : ""));
        }
        if (parts.isEmpty()) {
            return PageResponse.of(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0);
        }
        String union = String.join(" UNION ALL ", parts);
        List<Row> rows = jdbc.query("SELECT * FROM (" + union + ") x ORDER BY at DESC, id LIMIT :limit OFFSET :offset",
                params, (rs, i) -> new Row(rs.getObject(1, UUID.class), Kind.valueOf(rs.getString(2)),
                        rs.getTimestamp(3).toInstant()));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + union + ") x", params, Long.class);

        Map<UUID, Visitor> visitorById = load(rows, Kind.VISITOR, visitors::findAllById, Visitor::getId);
        Map<UUID, Delivery> deliveryById = load(rows, Kind.DELIVERY, deliveries::findAllById, Delivery::getId);
        Map<UUID, StaffAttendance> attendanceById = load(rows, Kind.STAFF, attendance::findAllById, StaffAttendance::getId);
        Map<UUID, Complaint> complaintById = load(rows, Kind.COMPLAINT, complaints::findAllById, Complaint::getId);

        List<ActivityItem> items = new ArrayList<>();
        for (Row row : rows) {
            ActivityItem item = switch (row.kind()) {
                case VISITOR -> visitorItem(visitorById.get(row.id()), row.at());
                case DELIVERY -> deliveryItem(deliveryById.get(row.id()), row.at());
                case STAFF -> staffItem(attendanceById.get(row.id()), row.at());
                case COMPLAINT -> complaintItem(complaintById.get(row.id()), row.at());
            };
            if (item != null) {
                items.add(item);
            }
        }
        return PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(), total == null ? 0 : total);
    }

    private record Row(UUID id, Kind kind, Instant at) {
    }

    private static <T> Map<UUID, T> load(List<Row> rows, Kind kind, Function<List<UUID>, List<T>> finder,
                                        Function<T, UUID> idOf) {
        List<UUID> ids = rows.stream().filter(r -> r.kind() == kind).map(Row::id).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return finder.apply(ids).stream().collect(Collectors.toMap(idOf, Function.identity()));
    }

    private static ActivityItem visitorItem(Visitor v, Instant at) {
        if (v == null) {
            return null;
        }
        String title = switch (v.displayStatus()) {
            case CHECKED_OUT -> v.getName() + " left";
            case CHECKED_IN -> v.getName() + " entered";
            case APPROVED -> v.getName() + " was approved";
            case REJECTED -> v.getName() + " was rejected";
            case PRE_APPROVED -> v.getName() + " was invited";
            case PENDING -> v.getName() + " is waiting at the gate";
            case CANCELLED -> v.getName() + "'s visit was cancelled";
            case EXPIRED -> (v.isInvitation() ? "Invite for " : "Request for ") + v.getName() + " expired";
        };
        return new ActivityItem("VISITOR:" + v.getId(), Kind.VISITOR, title,
                v.getVisitorType().label() + " · " + v.getFlat().toRef().label(), at, v.getId(), v.displayStatus().name());
    }

    private static ActivityItem deliveryItem(Delivery d, Instant at) {
        if (d == null) {
            return null;
        }
        String title = switch (d.displayStatus()) {
            case PENDING -> d.getCompany() + " delivery is waiting";
            case LEFT_AT_GATE -> d.getCompany() + " parcel kept at the gate";
            case COMPLETED -> d.getCompany() + " delivery completed";
            case REJECTED -> d.getCompany() + " delivery was rejected";
            case ENTERED -> d.getCompany() + " delivery is inside";
            case APPROVED -> d.getCompany() + " delivery approved";
            case CANCELLED -> d.getCompany() + " delivery was cancelled";
        };
        return new ActivityItem("DELIVERY:" + d.getId(), Kind.DELIVERY, title,
                d.getCategory().label() + " · " + d.getFlat().toRef().label(), at, d.getId(), d.displayStatus().name());
    }

    private static ActivityItem staffItem(StaffAttendance a, Instant at) {
        if (a == null) {
            return null;
        }
        boolean out = a.getCheckOutAt() != null;
        return new ActivityItem("STAFF:" + a.getId(), Kind.STAFF,
                a.getStaff().getName() + (out ? " checked out" : " checked in"), a.getStaff().getCategory().label(), at,
                a.getStaff().getId(), out ? "OUTSIDE" : "INSIDE");
    }

    private static ActivityItem complaintItem(Complaint c, Instant at) {
        if (c == null) {
            return null;
        }
        String title = c.getStatus() == Complaint.Status.OPEN
                ? "Complaint raised: " + c.getTitle()
                : "Complaint " + c.getStatus().label().toLowerCase() + ": " + c.getTitle();
        return new ActivityItem("COMPLAINT:" + c.getId(), Kind.COMPLAINT, title, c.getTicketNumber(), at, c.getId(),
                c.getStatus().name());
    }
}
