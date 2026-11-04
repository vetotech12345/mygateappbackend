package com.mygateapp.domesticstaff.entity;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "domestic_staff")
@Getter
@Setter
public class DomesticStaff extends VersionedEntity {

    public enum Category {
        MAID("Maid"), DRIVER("Driver"), COOK("Cook"), CLEANER("Cleaner"), OTHER("Other");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Presence { INSIDE, OUTSIDE }

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "mobile", nullable = false, length = 15)
    private String mobile;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private Category category;

    @Column(name = "photo_file_id")
    private UUID photoFileId;

    @Column(name = "pass_number", length = 30)
    private String passNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ActiveStatus status = ActiveStatus.ACTIVE;

    @Column(name = "working_since")
    private LocalDate workingSince;

    @Column(name = "inside", nullable = false)
    private boolean inside;

    @Column(name = "last_check_in_at")
    private Instant lastCheckInAt;

    @Column(name = "last_check_out_at")
    private Instant lastCheckOutAt;

    @Column(name = "created_by")
    private UUID createdBy;
}
