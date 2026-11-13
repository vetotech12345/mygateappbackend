package com.mygateapp.parking.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.parking.enums.ParkingEnums.AllocationStatus;
import com.mygateapp.parking.enums.ParkingEnums.AllocationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Right to use a slot for a period. Live allocations of one slot never overlap (database exclusion constraint). */
@Entity
@Table(name = "parking_allocations")
@Getter
@Setter
public class ParkingAllocation extends VersionedEntity {

    @Column(name = "society_id", nullable = false, updatable = false)
    private UUID societyId;

    @Column(name = "slot_id", nullable = false)
    private UUID slotId;

    @Column(name = "flat_id")
    private UUID flatId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "request_id")
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_type", nullable = false, length = 20)
    private AllocationType allocationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AllocationStatus status;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "notes", length = 300)
    private String notes;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "ended_by")
    private UUID endedBy;

    @Column(name = "end_reason", length = 200)
    private String endReason;

    @Column(name = "created_by")
    private UUID createdBy;

    public boolean coversNow(Instant now) {
        return !now.isBefore(validFrom) && (validUntil == null || now.isBefore(validUntil));
    }
}
