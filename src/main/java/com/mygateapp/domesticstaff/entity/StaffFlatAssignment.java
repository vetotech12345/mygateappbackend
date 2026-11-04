package com.mygateapp.domesticstaff.entity;

import com.mygateapp.common.IdentifiedEntity;
import com.mygateapp.flats.entity.Flat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "domestic_staff_flat_assignments")
@Getter
@Setter
public class StaffFlatAssignment extends IdentifiedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flat_id", nullable = false)
    private Flat flat;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "ended_at")
    private Instant endedAt;
}
