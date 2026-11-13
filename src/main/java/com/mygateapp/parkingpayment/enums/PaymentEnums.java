package com.mygateapp.parkingpayment.enums;

import java.util.EnumSet;
import java.util.Set;

/** Pricing and payment enums (wire values match the Flutter parking module). */
public final class PaymentEnums {

    private PaymentEnums() {
    }

    public enum BillingUnit {
        HOURLY(60), DAILY(24 * 60), MONTHLY(30 * 24 * 60);

        private final int minutes;

        BillingUnit(int minutes) {
            this.minutes = minutes;
        }

        public int minutes() {
            return minutes;
        }
    }

    /** Who a rate plan is for. {@code PUBLIC} = external (non-resident) paid parking. */
    public enum RateAppliesTo { VISITOR, RESIDENT, PUBLIC, ALL }

    /** {@code PAID} is the master prompt's {@code CONFIRMED}. */
    public enum PaymentStatus { PENDING, PAID, FAILED, REFUNDED, WAIVED }

    public enum PaymentMethod {
        CASH, UPI_AT_GATE, CARD_AT_GATE, UPI, BANK_TRANSFER, ONLINE, WAIVER;

        /** Methods a guard may record at the gate. */
        public static final Set<PaymentMethod> GATE = EnumSet.of(CASH, UPI_AT_GATE, CARD_AT_GATE);
        /** Methods recorded by hand (gate or office). ONLINE needs a verified provider callback; WAIVER is an adjustment. */
        public static final Set<PaymentMethod> MANUAL = EnumSet.of(CASH, UPI_AT_GATE, CARD_AT_GATE, UPI, BANK_TRANSFER);
    }

    public enum PaymentPurpose { SESSION, PERMIT }

    public enum PaymentEventType { CREATED, CONFIRMED, FAILED, REFUNDED, WAIVED }
}
