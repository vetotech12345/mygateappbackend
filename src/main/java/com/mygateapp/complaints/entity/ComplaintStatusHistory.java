package com.mygateapp.complaints.entity;

import com.mygateapp.common.IdentifiedEntity;
import com.mygateapp.common.dto.Refs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "complaint_status_history")
@Getter
@Setter
public class ComplaintStatusHistory extends IdentifiedEntity {

    @Column(name = "complaint_id", nullable = false, updatable = false)
    private UUID complaintId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private Complaint.Status fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private Complaint.Status toStatus;

    @Column(name = "title", nullable = false, length = 150, updatable = false)
    private String title;

    @Column(name = "remarks", length = 500, updatable = false)
    private String remarks;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_by_name", length = 80, updatable = false)
    private String changedByName;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    public Refs.TimelineEvent toTimeline() {
        return new Refs.TimelineEvent(title, changedAt, changedByName, toStatus.name(), remarks);
    }
}
