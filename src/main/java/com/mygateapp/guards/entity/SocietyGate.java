package com.mygateapp.guards.entity;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.VersionedEntity;
import com.mygateapp.common.dto.Refs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "society_gates")
@Getter
@Setter
public class SocietyGate extends VersionedEntity {

    public enum GateType { MAIN, SERVICE, PEDESTRIAN, VEHICLE, OTHER }

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "gate_type", nullable = false, length = 20)
    private GateType gateType = GateType.MAIN;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ActiveStatus status = ActiveStatus.ACTIVE;

    public Refs.GateRef toRef() {
        return new Refs.GateRef(getId(), name);
    }
}
