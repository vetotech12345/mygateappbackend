package com.mygateapp.visitors.enums;

/** Visitor enums. Values are part of the API contract (UPPER_SNAKE_CASE on the wire). */
public final class VisitorEnums {

    private VisitorEnums() {
    }

    /** Union of the Flutter app's types and the master prompt's categories. */
    public enum VisitorType {
        GUEST("Guest"),
        FAMILY("Family / Friend"),
        SERVICE("Service provider"),
        DELIVERY("Delivery agent"),
        CAB("Cab"),
        OTHER("Other");

        private final String label;

        VisitorType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Source { WALK_IN, INVITATION }

    public enum ApprovalStatus { PENDING, APPROVED, REJECTED, CANCELLED, EXPIRED }

    /** Single status used by the Flutter app, derived from approval + movement + source. */
    public enum DisplayStatus { PENDING, APPROVED, REJECTED, PRE_APPROVED, CHECKED_IN, CHECKED_OUT, CANCELLED, EXPIRED }

    /** Flutter list scopes. */
    public enum Scope { PENDING, EXPECTED, INSIDE, HISTORY }

    public enum EventType { CREATED, INVITED, APPROVED, REJECTED, CANCELLED, EXPIRED, ENTERED, EXITED, PASS_VERIFIED }
}
