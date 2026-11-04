package com.mygateapp.residents.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.users.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * A resident's link to a flat — the "resident" resource of the API ({@code /residents/{id}} is this id).
 * Ended memberships are kept as occupancy history.
 */
@Entity
@Table(name = "flat_memberships")
@Getter
@Setter
public class FlatMembership extends VersionedEntity {

    public enum Occupancy { OWNER, TENANT }

    public enum Status { PENDING, ACTIVE, REJECTED, ENDED }

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flat_id", nullable = false)
    private Flat flat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "occupancy_type", nullable = false, length = 20)
    private Occupancy occupancy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "end_reason", length = 200)
    private String endReason;

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
