package com.mygateapp.parkingpayment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mygateapp.parkingpayment.enums.PaymentEnums.BillingUnit;
import com.mygateapp.parkingpayment.service.PricingCalculator.Charge;
import com.mygateapp.parkingpayment.service.PricingCalculator.Terms;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Server-side fee calculation: units, free minutes, grace periods, daily caps, overstay, tax and rounding. */
class PricingCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-11-12T08:00:00Z");

    private static Terms hourly(String rate, int free, int grace, String cap, String tax) {
        return new Terms(BillingUnit.HOURLY, new BigDecimal(rate), "INR", free, grace,
                cap == null ? null : new BigDecimal(cap), null, tax == null ? BigDecimal.ZERO : new BigDecimal(tax),
                tax == null ? null : "GST " + tax + "%");
    }

    private static Charge charge(Terms terms, Duration stay) {
        return PricingCalculator.calculate(terms, T0, T0.plus(stay), null, 0);
    }

    @Test
    void chargesStartedHoursBeyondTheGracePeriod() {
        Terms terms = hourly("30.00", 0, 15, null, null);
        assertThat(charge(terms, Duration.ofMinutes(60)).total()).isEqualByComparingTo("30.00");
        // 70 min: the started second hour is within the 15 min grace -> 1 hour
        assertThat(charge(terms, Duration.ofMinutes(70)).total()).isEqualByComparingTo("30.00");
        // 76 min: 16 min into the second hour -> 2 hours
        assertThat(charge(terms, Duration.ofMinutes(76)).total()).isEqualByComparingTo("60.00");
        // 10 min with a 15 min grace and no full hour -> free
        assertThat(charge(terms, Duration.ofMinutes(10)).total()).isEqualByComparingTo("0.00");
    }

    @Test
    void withoutGraceEveryStartedUnitIsCharged() {
        Terms terms = hourly("30.00", 0, 0, null, null);
        assertThat(charge(terms, Duration.ofMinutes(61)).total()).isEqualByComparingTo("60.00");
        // 61 seconds round up to 2 minutes, still one started hour
        assertThat(charge(terms, Duration.ofSeconds(61)).total()).isEqualByComparingTo("30.00");
    }

    @Test
    void freeMinutesAreDeductedFirst() {
        Terms terms = hourly("30.00", 120, 0, null, null);
        Charge twoHours = charge(terms, Duration.ofMinutes(120));
        assertThat(twoHours.total()).isEqualByComparingTo("0.00");
        assertThat(twoHours.billableMinutes()).isZero();
        Charge threeHours = charge(terms, Duration.ofMinutes(180));
        assertThat(threeHours.billableMinutes()).isEqualTo(60);
        assertThat(threeHours.total()).isEqualByComparingTo("30.00");
        assertThat(threeHours.lines()).extracting(PricingCalculator.Line::label).contains("First 2 h free");
    }

    @Test
    void societyGracePeriodMakesShortStaysFree() {
        Terms terms = hourly("30.00", 0, 0, null, null);
        Charge dropOff = PricingCalculator.calculate(terms, T0, T0.plus(Duration.ofMinutes(15)), null, 15);
        assertThat(dropOff.total()).isEqualByComparingTo("0.00");
        Charge longer = PricingCalculator.calculate(terms, T0, T0.plus(Duration.ofMinutes(16)), null, 15);
        assertThat(longer.total()).isEqualByComparingTo("30.00");
    }

    @Test
    void dailyCapLimitsEachDayOfHourlyParking() {
        Terms terms = hourly("30.00", 0, 0, "200.00", null);
        // 10 h * 30 = 300 > cap 200
        assertThat(charge(terms, Duration.ofHours(10)).total()).isEqualByComparingTo("200.00");
        // 5 h = 150, below the cap
        assertThat(charge(terms, Duration.ofHours(5)).total()).isEqualByComparingTo("150.00");
        // 1 full day (cap 200) + 2 h (60)
        assertThat(charge(terms, Duration.ofHours(26)).total()).isEqualByComparingTo("260.00");
        // 2 full days + 9 h (270 capped at 200)
        assertThat(charge(terms, Duration.ofHours(57)).total()).isEqualByComparingTo("600.00");
    }

    @Test
    void dailyAndMonthlyUnits() {
        Terms daily = new Terms(BillingUnit.DAILY, new BigDecimal("150.00"), "INR", 0, 60, null, null, BigDecimal.ZERO, null);
        assertThat(charge(daily, Duration.ofHours(24)).total()).isEqualByComparingTo("150.00");
        assertThat(charge(daily, Duration.ofHours(25)).total()).isEqualByComparingTo("150.00");
        assertThat(charge(daily, Duration.ofHours(26)).total()).isEqualByComparingTo("300.00");

        Terms monthly = new Terms(BillingUnit.MONTHLY, new BigDecimal("1500.00"), "INR", 0, 0, null, null, BigDecimal.ZERO, null);
        assertThat(charge(monthly, Duration.ofDays(30)).total()).isEqualByComparingTo("1500.00");
        assertThat(charge(monthly, Duration.ofDays(90)).total()).isEqualByComparingTo("4500.00");
    }

    @Test
    void overstayIsChargedPerStartedHourAfterTheWindow() {
        Terms terms = new Terms(BillingUnit.HOURLY, new BigDecimal("20.00"), "INR", 0, 0, null, new BigDecimal("50.00"),
                BigDecimal.ZERO, null);
        Instant validUntil = T0.plus(Duration.ofHours(2));
        Charge charge = PricingCalculator.calculate(terms, T0, T0.plus(Duration.ofMinutes(150)), validUntil, 0);
        // 3 started hours * 20 + 1 overstay hour * 50
        assertThat(charge.total()).isEqualByComparingTo("110.00");
        assertThat(charge.lines()).extracting(PricingCalculator.Line::label).anyMatch(l -> l.startsWith("Overstay 1 h"));
    }

    @Test
    void taxIsRoundedHalfUpToPaise() {
        Terms terms = hourly("30.00", 0, 0, null, "18.00");
        Charge charge = charge(terms, Duration.ofHours(2));
        assertThat(charge.subtotal()).isEqualByComparingTo("60.00");
        assertThat(charge.tax()).isEqualByComparingTo("10.80");
        assertThat(charge.total()).isEqualByComparingTo("70.80");
        assertThat(charge.taxLabel()).isEqualTo("GST 18.00%");

        Terms odd = hourly("33.33", 0, 0, null, "18.00");
        // 33.33 * 18% = 5.9994 -> 6.00
        assertThat(charge(odd, Duration.ofHours(1)).tax()).isEqualByComparingTo("6.00");
        assertThat(charge(odd, Duration.ofHours(1)).total().scale()).isEqualTo(2);
    }

    @Test
    void noChargeForNonPositiveDurations() {
        Terms terms = hourly("30.00", 0, 0, null, null);
        assertThat(PricingCalculator.calculate(terms, T0, T0, null, 0).total()).isEqualByComparingTo("0.00");
        assertThat(PricingCalculator.calculate(terms, T0, T0.minusSeconds(60), null, 0).total()).isEqualByComparingTo("0.00");
    }
}
