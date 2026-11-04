package com.mygateapp.guards.entity;

import com.mygateapp.common.IdentifiedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** One on-duty period of a guard (started when the guard toggles duty on). */
@Entity
@Table(name = "guard_duty_logs")
@Getter
@Setter
public class GuardDutyLog extends IdentifiedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "guard_id", nullable = false)
    private UUID guardId;

    @Column(name = "gate_id")
    private UUID gateId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;
}
