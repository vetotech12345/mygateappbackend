package com.mygateapp.notices.entity;

import com.mygateapp.common.IdentifiedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "notice_attachments")
@Getter
@Setter
public class NoticeAttachment extends IdentifiedEntity {

    @Column(name = "notice_id", nullable = false)
    private UUID noticeId;

    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
