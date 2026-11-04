package com.mygateapp.notifications.enums;

/** Notification types. Values match the Flutter {@code NotificationType} enum. */
public enum NotificationType {
    VISITOR_APPROVAL_REQUEST(true),
    VISITOR_APPROVED(false),
    VISITOR_REJECTED(false),
    VISITOR_CHECKED_IN(false),
    DELIVERY_ARRIVAL(false),
    DOMESTIC_HELP_ARRIVAL(false),
    COMPLAINT_UPDATE(false),
    ANNOUNCEMENT(false),
    GENERAL(false);

    /** Mandatory types are always pushed: gate entry depends on them. */
    private final boolean mandatory;

    NotificationType(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public boolean mandatory() {
        return mandatory;
    }
}
