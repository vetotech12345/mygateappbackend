package com.mygateapp.complaints.entity;

import com.mygateapp.common.VersionedEntity;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.users.entity.User;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "complaints")
@Getter
@Setter
public class Complaint extends VersionedEntity {

    public enum Category {
        PLUMBING("Plumbing"), ELECTRICAL("Electrical"), LIFT("Lift"), SECURITY("Security"),
        HOUSEKEEPING("Housekeeping"), PARKING("Parking"), AMENITIES("Amenities"), OTHER("Other");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Status {
        OPEN("Open"), IN_PROGRESS("In progress"), RESOLVED("Resolved"), CLOSED("Closed");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Allowed status changes. RESOLVED -> IN_PROGRESS reopens a complaint. */
    public static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.OPEN, Set.of(Status.IN_PROGRESS, Status.RESOLVED, Status.CLOSED),
            Status.IN_PROGRESS, Set.of(Status.RESOLVED, Status.CLOSED),
            Status.RESOLVED, Set.of(Status.IN_PROGRESS, Status.CLOSED),
            Status.CLOSED, Set.of());

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flat_id", nullable = false)
    private Flat flat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raised_by", nullable = false)
    private User raisedBy;

    @Column(name = "ticket_number", nullable = false, length = 20)
    private String ticketNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private Category category;

    @Column(name = "title", nullable = false, length = 80)
    private String title;

    @Column(name = "description", nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    @Column(name = "admin_remarks", length = 500)
    private String adminRemarks;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @ElementCollection
    @CollectionTable(name = "complaint_attachments", joinColumns = @JoinColumn(name = "complaint_id"))
    @OrderColumn(name = "position")
    @Column(name = "file_id", nullable = false)
    private List<UUID> attachmentFileIds = new ArrayList<>();

    public static boolean canTransition(Status from, Status to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
