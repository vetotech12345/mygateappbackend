package com.mygateapp.notices.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Per-user read state of a notice (written idempotently with INSERT ... ON CONFLICT DO NOTHING). */
@Entity
@Table(name = "notice_read_receipts")
@IdClass(NoticeReadReceipt.Key.class)
@Getter
@Setter
public class NoticeReadReceipt {

    @Id
    @Column(name = "notice_id")
    private UUID noticeId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    public record Key(UUID noticeId, UUID userId) implements Serializable {

        public Key() {
            this(null, null);
        }
    }
}
