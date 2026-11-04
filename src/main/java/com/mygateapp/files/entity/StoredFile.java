package com.mygateapp.files.entity;

import com.mygateapp.common.IdentifiedEntity;
import com.mygateapp.files.service.FileStorage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Metadata of an uploaded file. The bytes live in {@link FileStorage} under {@code storageKey}. */
@Entity
@Table(name = "stored_files")
@Getter
@Setter
public class StoredFile extends IdentifiedEntity {

    public enum Purpose { VISITOR_PHOTO, COMPLAINT_ATTACHMENT, NOTICE_ATTACHMENT, STAFF_PHOTO, AVATAR }

    @Column(name = "society_id")
    private UUID societyId;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private Purpose purpose;

    @Column(name = "original_name", length = 200)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 300)
    private String storageKey;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
