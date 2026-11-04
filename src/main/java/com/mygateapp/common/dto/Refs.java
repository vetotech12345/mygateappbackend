package com.mygateapp.common.dto;

import java.time.Instant;
import java.util.UUID;

/** Small reference DTOs embedded in larger responses. Field names match the Flutter models. */
public final class Refs {

    private Refs() {
    }

    public record SocietyRef(UUID id, String name, String city) {
    }

    /**
     * {@code tower} is the tower code (e.g. "A"); {@code label} is the display form ("A-1204" or "A-East-1204").
     */
    public record FlatRef(UUID id, String tower, String wing, String number, String label) {

        public static FlatRef of(UUID id, String towerCode, String wingName, String number) {
            String label = wingName == null ? towerCode + "-" + number : towerCode + "-" + wingName + "-" + number;
            return new FlatRef(id, towerCode, wingName, number, label);
        }
    }

    public record GateRef(UUID id, String name) {
    }

    public record UserRef(UUID id, String name) {
    }

    /** One entry in a status history / timeline. {@code status} is the status the item moved to, if any. */
    public record TimelineEvent(String title, Instant at, String actor, String status, String description) {
    }
}
