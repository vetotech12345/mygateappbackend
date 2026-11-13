package com.mygateapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * The production clock bean. Integration tests replace it with a test clock, so it needs its own test: a sub-millisecond
 * {@code Clock.tick} once made every rate-limited request fail with an ArithmeticException on Java 21.
 */
class ClockConfigTest {

    private final Clock clock = new CoreConfig().clock();

    @Test
    void millisWorks() {
        assertThatCode(clock::millis).doesNotThrowAnyException();
        assertThat(Math.abs(clock.millis() - System.currentTimeMillis())).isLessThan(5_000);
    }

    @Test
    void instantsHaveMicrosecondPrecision() {
        Instant now = clock.instant();
        assertThat(now).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Z"));
    }

    @Test
    void zonedViewKeepsPrecisionAndMillis() {
        Clock zoned = clock.withZone(ZoneId.of("Asia/Kolkata"));
        assertThat(zoned.getZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThatCode(zoned::millis).doesNotThrowAnyException();
        // Read once: two reads could straddle a microsecond tick.
        Instant now = zoned.instant();
        assertThat(now).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
    }
}
