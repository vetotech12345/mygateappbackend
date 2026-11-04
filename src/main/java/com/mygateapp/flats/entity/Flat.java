package com.mygateapp.flats.entity;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.VersionedEntity;
import com.mygateapp.common.dto.Refs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Society -> Tower -> (Wing) -> floor -> Flat. Floors are a number on the flat. */
@Entity
@Table(name = "flats")
@Getter
@Setter
public class Flat extends VersionedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tower_id", nullable = false)
    private Tower tower;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wing_id")
    private Wing wing;

    @Column(name = "floor_number", nullable = false)
    private int floorNumber;

    @Column(name = "flat_number", nullable = false, length = 20)
    private String flatNumber;

    @Column(name = "flat_type", length = 20)
    private String flatType;

    @Column(name = "area_sqft")
    private Integer areaSqft;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ActiveStatus status = ActiveStatus.ACTIVE;

    public Refs.FlatRef toRef() {
        return Refs.FlatRef.of(getId(), tower.getCode(), wing == null ? null : wing.getName(), flatNumber);
    }

    public boolean isActive() {
        return status == ActiveStatus.ACTIVE;
    }
}
