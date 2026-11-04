package com.mygateapp.common;

import com.mygateapp.exception.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Day boundaries in a society's time zone ("today" for a society in India starts at 00:00 IST). */
public final class TimeRanges {

    public static final int MAX_REPORT_DAYS = 366;

    /**
     * Open bounds for optional time filters. PostgreSQL cannot type a NULL timestamp in {@code :x IS NULL}, so
     * queries always receive concrete instants.
     */
    public static final Instant MIN = Instant.parse("1970-01-01T00:00:00Z");
    public static final Instant MAX = Instant.parse("9999-12-31T00:00:00Z");

    public static Instant orMin(Instant value) {
        return value == null ? MIN : value;
    }

    public static Instant orMax(Instant value) {
        return value == null ? MAX : value;
    }

    private TimeRanges() {
    }

    public record Range(Instant from, Instant to) {
    }

    public static Range today(Clock clock, ZoneId zone) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return day(today, zone);
    }

    public static Range day(LocalDate date, ZoneId zone) {
        return new Range(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /** Inclusive local-date range used by reports; defaults to the last 7 days. */
    public static Range reportRange(LocalDate from, LocalDate to, Clock clock, ZoneId zone) {
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(zone));
        LocalDate start = from != null ? from : end.minusDays(6);
        if (start.isAfter(end)) {
            throw ApiException.field("from", "'from' must not be after 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_REPORT_DAYS) {
            throw ApiException.field("from", "Report ranges are limited to " + MAX_REPORT_DAYS + " days");
        }
        return new Range(start.atStartOfDay(zone).toInstant(), end.plusDays(1).atStartOfDay(zone).toInstant());
    }
}
