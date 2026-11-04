package com.mygateapp.flats.entity;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "towers")
@Getter
@Setter
public class Tower extends VersionedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    /** Short code used in flat labels, e.g. "A" in "A-1204". */
    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "floor_count")
    private Integer floorCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ActiveStatus status = ActiveStatus.ACTIVE;
}
