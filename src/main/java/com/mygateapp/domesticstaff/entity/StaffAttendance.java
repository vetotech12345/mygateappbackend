package com.mygateapp.domesticstaff.entity;

import com.mygateapp.common.IdentifiedEntity;
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

/** One check-in / check-out pair. At most one open row per staff member (partial unique index). */
@Entity
@Table(name = "domestic_staff_attendance")
@Getter
@Setter
public class StaffAttendance extends IdentifiedEntity {

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private DomesticStaff staff;

    @Column(name = "check_in_at", nullable = false)
    private Instant checkInAt;

    @Column(name = "check_in_gate_id")
    private UUID checkInGateId;

    @Column(name = "checked_in_by")
    private UUID checkedInBy;

    @Column(name = "check_out_at")
    private Instant checkOutAt;

    @Column(name = "check_out_gate_id")
    private UUID checkOutGateId;

    @Column(name = "checked_out_by")
    private UUID checkedOutBy;
}
