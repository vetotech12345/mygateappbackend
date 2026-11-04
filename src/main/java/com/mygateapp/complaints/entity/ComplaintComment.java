package com.mygateapp.complaints.entity;

import com.mygateapp.common.IdentifiedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "complaint_comments")
@Getter
@Setter
public class ComplaintComment extends IdentifiedEntity {

    @Column(name = "complaint_id", nullable = false)
    private UUID complaintId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "author_name", length = 80)
    private String authorName;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    /** Internal notes are visible to society administrators only. */
    @Column(name = "internal", nullable = false)
    private boolean internal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
