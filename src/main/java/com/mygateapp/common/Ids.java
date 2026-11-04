package com.mygateapp.common;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class Ids {

    /** Placeholder that never matches a row; used so that {@code IN (:ids)} is never rendered with an empty list. */
    public static final UUID NIL = new UUID(0L, 0L);

    private Ids() {
    }

    public static Collection<UUID> orNil(Collection<UUID> ids) {
        return ids == null || ids.isEmpty() ? List.of(NIL) : ids;
    }
}
