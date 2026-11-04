package com.mygateapp.notices.entity;

import com.mygateapp.common.VersionedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "notices")
@Getter
@Setter
public class Notice extends VersionedEntity {

    public enum Category {
        GENERAL("General"), MAINTENANCE("Maintenance"), EVENT("Events"), FINANCE("Finance"), SAFETY("Safety");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Who sees the notice. TOWERS = residents of the listed towers. */
    public enum Audience { ALL, RESIDENTS, GUARDS, TOWERS }

    public enum Status { DRAFT, PUBLISHED }

    @Column(name = "society_id", nullable = false)
    private UUID societyId;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "body", nullable = false, length = 5000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private Category category;

    @Column(name = "important", nullable = false)
    private boolean important;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 20)
    private Audience audience = Audience.RESIDENTS;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "posted_by_label", nullable = false, length = 80)
    private String postedByLabel = "Society Office";

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "notice_audience_towers", joinColumns = @JoinColumn(name = "notice_id"))
    @Column(name = "tower_id", nullable = false)
    private Set<UUID> towerIds = new HashSet<>();

    public boolean isPublished() {
        return status == Status.PUBLISHED;
    }
}
