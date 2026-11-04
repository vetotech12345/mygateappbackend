package com.mygateapp.common.gate;

import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import java.time.Instant;
import java.util.UUID;

/**
 * The single place where entry/exit transitions are validated, shared by visitors and deliveries.
 * Callers must hold a row lock on the owning entity so that duplicate requests are serialised.
 *
 * <pre>NOT_ENTERED --entry--> INSIDE --exit--> EXITED</pre>
 */
public final class GateMovementPolicy {

    /** Subject-specific error codes (visitor vs delivery wording). */
    public record Codes(ErrorCode alreadyInside, ErrorCode closed, ErrorCode alreadyExited, ErrorCode notInside) {
    }

    public static final Codes VISITOR = new Codes(ErrorCode.VISITOR_ALREADY_INSIDE, ErrorCode.VISIT_CLOSED,
            ErrorCode.VISITOR_ALREADY_EXITED, ErrorCode.VISITOR_NOT_INSIDE);
    public static final Codes DELIVERY = new Codes(ErrorCode.DELIVERY_ALREADY_INSIDE, ErrorCode.DELIVERY_ALREADY_EXITED,
            ErrorCode.DELIVERY_ALREADY_EXITED, ErrorCode.DELIVERY_NOT_INSIDE);

    private GateMovementPolicy() {
    }

    public static void recordEntry(GateMovement movement, Instant at, UUID gateId, UUID recordedBy, Codes codes) {
        switch (movement.getStatus()) {
            case INSIDE -> throw new ApiException(codes.alreadyInside());
            case EXITED -> throw new ApiException(codes.closed());
            case NOT_ENTERED -> movement.markEntered(at, gateId, recordedBy);
        }
    }

    public static void recordExit(GateMovement movement, Instant at, UUID gateId, UUID recordedBy, Codes codes) {
        switch (movement.getStatus()) {
            case NOT_ENTERED -> throw new ApiException(codes.notInside());
            case EXITED -> throw new ApiException(codes.alreadyExited());
            case INSIDE -> {
                // Clock skew between nodes must never produce exit < entry.
                Instant exitAt = at.isBefore(movement.getEntryAt()) ? movement.getEntryAt() : at;
                movement.markExited(exitAt, gateId, recordedBy);
            }
        }
    }
}
