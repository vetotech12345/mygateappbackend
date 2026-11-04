package com.mygateapp.common.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GateMovementPolicyTest {

    private final UUID gate = UUID.randomUUID();
    private final UUID guard = UUID.randomUUID();
    private final Instant t0 = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void entryThenExit() {
        GateMovement movement = new GateMovement();
        GateMovementPolicy.recordEntry(movement, t0, gate, guard, GateMovementPolicy.VISITOR);
        assertThat(movement.getStatus()).isEqualTo(MovementStatus.INSIDE);
        assertThat(movement.getEntryAt()).isEqualTo(t0);
        assertThat(movement.getEntryRecordedBy()).isEqualTo(guard);

        GateMovementPolicy.recordExit(movement, t0.plusSeconds(600), gate, guard, GateMovementPolicy.VISITOR);
        assertThat(movement.getStatus()).isEqualTo(MovementStatus.EXITED);
        assertThat(movement.getExitAt()).isEqualTo(t0.plusSeconds(600));
    }

    @Test
    void exitBeforeEntryIsRejected() {
        GateMovement movement = new GateMovement();
        assertThatThrownBy(() -> GateMovementPolicy.recordExit(movement, t0, gate, guard, GateMovementPolicy.VISITOR))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.VISITOR_NOT_INSIDE);
    }

    @Test
    void duplicatesAreRejectedWithSubjectSpecificCodes() {
        GateMovement movement = new GateMovement();
        GateMovementPolicy.recordEntry(movement, t0, gate, guard, GateMovementPolicy.DELIVERY);
        assertThatThrownBy(() -> GateMovementPolicy.recordEntry(movement, t0, gate, guard, GateMovementPolicy.DELIVERY))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.DELIVERY_ALREADY_INSIDE);
        GateMovementPolicy.recordExit(movement, t0.plusSeconds(60), gate, guard, GateMovementPolicy.DELIVERY);
        assertThatThrownBy(() -> GateMovementPolicy.recordExit(movement, t0, gate, guard, GateMovementPolicy.DELIVERY))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.DELIVERY_ALREADY_EXITED);
        assertThatThrownBy(() -> GateMovementPolicy.recordEntry(movement, t0, gate, guard, GateMovementPolicy.VISITOR))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.VISIT_CLOSED);
    }

    @Test
    void exitIsNeverEarlierThanEntryEvenWithClockSkew() {
        GateMovement movement = new GateMovement();
        GateMovementPolicy.recordEntry(movement, t0, gate, guard, GateMovementPolicy.VISITOR);
        GateMovementPolicy.recordExit(movement, t0.minusSeconds(5), gate, guard, GateMovementPolicy.VISITOR);
        assertThat(movement.getExitAt()).isEqualTo(t0);
    }
}
