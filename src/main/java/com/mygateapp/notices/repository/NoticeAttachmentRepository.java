package com.mygateapp.notices.repository;

import com.mygateapp.notices.entity.NoticeAttachment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeAttachmentRepository extends JpaRepository<NoticeAttachment, UUID> {

    List<NoticeAttachment> findByNoticeIdInOrderByCreatedAt(Collection<UUID> noticeIds);
}
