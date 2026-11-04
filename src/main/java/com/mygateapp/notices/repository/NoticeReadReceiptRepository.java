package com.mygateapp.notices.repository;

import com.mygateapp.notices.entity.NoticeReadReceipt;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeReadReceiptRepository extends JpaRepository<NoticeReadReceipt, NoticeReadReceipt.Key> {

    @Query("SELECT r.noticeId FROM NoticeReadReceipt r WHERE r.userId = :userId AND r.noticeId IN :noticeIds")
    List<UUID> findReadNoticeIds(@Param("userId") UUID userId, @Param("noticeIds") Collection<UUID> noticeIds);
}
