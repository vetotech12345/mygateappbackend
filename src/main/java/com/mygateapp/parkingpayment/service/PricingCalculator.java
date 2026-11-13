package com.mygateapp.parkingpayment.service;

import com.mygateapp.parkingpayment.enums.PaymentEnums.BillingUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side parking charge calculation. Pure and deterministic: money is {@link BigDecimal} with two decimals and
 * {@code HALF_UP} rounding, durations come from server instants.
 *
 * <ol>
 *   <li><b>Society grace</b>: a stay no longer than {@code societyGraceMinutes} is free (drop-off / pick-up).</li>
 *   <li><b>Free minutes</b> of the rate are deducted from the stay.</li>
 *   <li>The rest is billed in units (60 min, 1 day, 30 days). A started unit is charged only when it exceeds the
 *       rate's <b>grace period</b>.</li>
 *   <li><b>Daily cap</b> (hourly plans): each full or partial 24 h block of billable time costs at most the cap.</li>
 *   <li><b>Overstay</b>: time after {@code validUntil} (beyond the grace period) is charged per started hour at the
 *       overstay rate, when the plan defines one.</li>
 *   <li><b>Tax</b> = subtotal × tax% rounded to 2 decimals; total = subtotal + tax.</li>
 * </ol>
 */
public final class PricingCalculator {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int DAY_MINUTES = 24 * 60;

    private PricingCalculator() {
    }

    /** The pricing terms of a rate plan (live plan or a session's snapshot). */
    public record Terms(BillingUnit unit, BigDecimal baseRate, String currency, int freeMinutes, int graceMinutes,
                        BigDecimal dailyCap, BigDecimal overstayHourlyRate, BigDecimal taxPercent, String taxLabel) {
    }

    public record Line(String label, BigDecimal amount) {
    }

    public record Charge(long totalMinutes, long billableMinutes, List<Line> lines, BigDecimal subtotal, BigDecimal tax,
                         String taxLabel, BigDecimal total) {
    }

    public static Charge calculate(Terms terms, Instant from, Instant until, Instant validUntil, int societyGraceMinutes) {
        long totalMinutes = ceilMinutes(from, until);
        List<Line> lines = new ArrayList<>();
        if (totalMinutes <= societyGraceMinutes) {
            lines.add(new Line("Within the free " + societyGraceMinutes + " min grace period", zero()));
            return finish(terms, totalMinutes, 0, lines);
        }
        long billable = Math.max(0, totalMinutes - terms.freeMinutes());
        if (terms.freeMinutes() > 0) {
            lines.add(new Line("First " + describeMinutes(terms.freeMinutes()) + " free", zero()));
        }
        if (billable > 0) {
            if (terms.unit() == BillingUnit.HOURLY && terms.dailyCap() != null) {
                addCappedHourly(terms, billable, lines);
            } else {
                long units = units(billable, terms.unit().minutes(), terms.graceMinutes());
                if (units > 0) {
                    lines.add(new Line(units + " " + unitLabel(terms.unit(), units) + " × " + format(terms.baseRate(), terms.currency()),
                            money(terms.baseRate().multiply(BigDecimal.valueOf(units)))));
                }
            }
        }
        if (validUntil != null && terms.overstayHourlyRate() != null && until.isAfter(validUntil)) {
            long overMinutes = ceilMinutes(validUntil, until);
            long overHours = units(overMinutes, 60, terms.graceMinutes());
            if (overHours > 0) {
                lines.add(new Line("Overstay " + overHours + " h × " + format(terms.overstayHourlyRate(), terms.currency()),
                        money(terms.overstayHourlyRate().multiply(BigDecimal.valueOf(overHours)))));
            }
        }
        return finish(terms, totalMinutes, billable, lines);
    }

    private static void addCappedHourly(Terms terms, long billable, List<Line> lines) {
        long fullDays = billable / DAY_MINUTES;
        long remainder = billable % DAY_MINUTES;
        BigDecimal dayCharge = terms.baseRate().multiply(BigDecimal.valueOf(24)).min(terms.dailyCap());
        if (fullDays > 0) {
            lines.add(new Line(fullDays + " " + (fullDays == 1 ? "day" : "days") + " × " + format(dayCharge, terms.currency())
                    + " (daily cap)", money(dayCharge.multiply(BigDecimal.valueOf(fullDays)))));
        }
        long hours = units(remainder, 60, terms.graceMinutes());
        if (hours > 0) {
            BigDecimal hourly = terms.baseRate().multiply(BigDecimal.valueOf(hours));
            if (hourly.compareTo(terms.dailyCap()) > 0) {
                lines.add(new Line(hours + " h (capped at " + format(terms.dailyCap(), terms.currency()) + ")",
                        money(terms.dailyCap())));
            } else {
                lines.add(new Line(hours + " h × " + format(terms.baseRate(), terms.currency()), money(hourly)));
            }
        }
    }

    private static Charge finish(Terms terms, long totalMinutes, long billable, List<Line> lines) {
        BigDecimal subtotal = money(lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal tax = terms.taxPercent() == null || terms.taxPercent().signum() == 0 ? zero()
                : money(subtotal.multiply(terms.taxPercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP));
        String taxLabel = tax.signum() == 0 && (terms.taxPercent() == null || terms.taxPercent().signum() == 0) ? null
                : terms.taxLabel() != null ? terms.taxLabel() : "Tax " + terms.taxPercent().stripTrailingZeros().toPlainString() + "%";
        return new Charge(totalMinutes, billable, List.copyOf(lines), subtotal, tax, taxLabel, money(subtotal.add(tax)));
    }

    /** Whole units for {@code minutes}: full units plus one for a remainder longer than {@code grace}. */
    static long units(long minutes, int unitMinutes, int grace) {
        if (minutes <= 0) {
            return 0;
        }
        long full = minutes / unitMinutes;
        long remainder = minutes % unitMinutes;
        return remainder > grace ? full + 1 : full;
    }

    /** Minutes between two instants, rounded up (61 s = 2 min). Negative spans are 0. */
    static long ceilMinutes(Instant from, Instant until) {
        long seconds = Duration.between(from, until).toSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return (seconds + 59) / 60;
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    static String format(BigDecimal amount, String currency) {
        String value = money(amount).toPlainString();
        return "INR".equals(currency) ? "₹" + value : currency + " " + value;
    }

    private static String unitLabel(BillingUnit unit, long count) {
        return switch (unit) {
            case HOURLY -> "h";
            case DAILY -> count == 1 ? "day" : "days";
            case MONTHLY -> count == 1 ? "month" : "months";
        };
    }

    private static String describeMinutes(int minutes) {
        return minutes % 60 == 0 ? (minutes / 60) + " h" : minutes + " min";
    }
}
