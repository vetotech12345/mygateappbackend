package com.mygateapp.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Clock whose instants are truncated to microseconds, matching PostgreSQL's timestamp precision, so a value
 * serialises identically before and after it is stored and read back.
 *
 * <p>Not {@code Clock.tick(base, Duration.ofNanos(1000))}: on Java 21 that clock's {@code millis()} computes the
 * tick in whole milliseconds (0) and throws {@link ArithmeticException}.
 */
public final class MicrosecondClock extends Clock {

    private final Clock base;

    public MicrosecondClock(Clock base) {
        this.base = base;
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MicrosecondClock(base.withZone(zone));
    }

    @Override
    public Instant instant() {
        return base.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public long millis() {
        return base.millis();
    }
}
