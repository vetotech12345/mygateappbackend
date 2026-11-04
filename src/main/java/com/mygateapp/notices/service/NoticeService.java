package com.mygateapp.notices.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.Ids;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.files.entity.StoredFile;
import com.mygateapp.files.service.FileService;
import com.mygateapp.flats.entity.Tower;
import com.mygateapp.flats.repository.TowerRepository;
import com.mygateapp.notices.dto.NoticeDtos;
import com.mygateapp.notices.entity.Notice;
import com.mygateapp.notices.entity.NoticeAttachment;
import com.mygateapp.notices.repository.NoticeAttachmentRepository;
import com.mygateapp.notices.repository.NoticeReadReceiptRepository;
import com.mygateapp.notices.repository.NoticeRepository;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.notifications.enums.NotificationType;
import com.mygateapp.notifications.service.NotificationService;
import com.mygateapp.residents.repository.FlatMembershipRepository;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.Permission;
import com.mygateapp.security.Role;
import com.mygateapp.security.UserAccess;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.society.repository.SocietyMembershipRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Society notices. Admins draft, edit, publish and (soft) delete; members read the published notices addressed to them.
 * Publishing notifies the audience ({@code ANNOUNCEMENT}). Read state is per user.
 */
@Service
public class NoticeService {

    private final NoticeRepository notices;
    private final NoticeAttachmentRepository attachments;
    private final NoticeReadReceiptRepository receipts;
    private final TowerRepository towers;
    private final SocietyMembershipRepository memberships;
    private final FlatMembershipRepository flatMemberships;
    private final FileService files;
    private final NotificationService notifications;
    private final AccessService access;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NoticeService(NoticeRepository notices, NoticeAttachmentRepository attachments, NoticeReadReceiptRepository receipts,
                         TowerRepository towers, SocietyMembershipRepository memberships,
                         FlatMembershipRepository flatMemberships, FileService files, NotificationService notifications,
                         AccessService access, AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.notices = notices;
        this.attachments = attachments;
        this.receipts = receipts;
        this.towers = towers;
        this.memberships = memberships;
        this.flatMemberships = flatMemberships;
        this.files = files;
        this.notifications = notifications;
        this.access = access;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- admin

    @Transactional
    public NoticeDtos.NoticeResponse create(NoticeDtos.NoticeRequest request) {
        UUID societyId = access.resolveSocietyId(request.societyId());
        access.require(societyId, Permission.NOTICE_MANAGE);
        Notice notice = new Notice();
        notice.setSocietyId(societyId);
        notice.setCreatedBy(access.currentUserId());
        apply(notice, request);
        notices.save(notice);
        audit.record(societyId, "NOTICE_CREATED", "NOTICE", notice.getId(), "Notice \"" + notice.getTitle() + "\" created", Map.of());
        if (Boolean.TRUE.equals(request.publish())) {
            publishInternal(notice);
        }
        return toResponse(notice, false);
    }

    @Transactional
    public NoticeDtos.NoticeResponse update(UUID id, NoticeDtos.NoticeRequest request) {
        Notice notice = requireForManage(id);
        apply(notice, request);
        notice.setUpdatedBy(access.currentUserId());
        audit.record(notice.getSocietyId(), "NOTICE_UPDATED", "NOTICE", id, "Notice \"" + notice.getTitle() + "\" updated", Map.of());
        return toResponse(notice, false);
    }

    @Transactional
    public NoticeDtos.NoticeResponse publish(UUID id) {
        Notice notice = requireForManage(id);
        if (notice.isPublished()) {
            throw new ApiException(ErrorCode.NOTICE_ALREADY_PUBLISHED);
        }
        publishInternal(notice);
        return toResponse(notice, false);
    }

    @Transactional
    public void delete(UUID id) {
        Notice notice = requireForManage(id);
        notice.setDeletedAt(clock.instant());
        notice.setUpdatedBy(access.currentUserId());
        audit.record(notice.getSocietyId(), "NOTICE_DELETED", "NOTICE", id, "Notice \"" + notice.getTitle() + "\" deleted", Map.of());
    }

    @Transactional
    public NoticeDtos.NoticeResponse addAttachment(UUID id, MultipartFile file, String displayName) {
        Notice notice = requireForManage(id);
        StoredFile stored = files.storeDocument(file, StoredFile.Purpose.NOTICE_ATTACHMENT, notice.getSocietyId(),
                access.currentUserId());
        NoticeAttachment attachment = new NoticeAttachment();
        attachment.setNoticeId(id);
        attachment.setFileId(stored.getId());
        String name = Strings.trimToNull(displayName);
        attachment.setDisplayName(name != null ? name : stored.getOriginalName() != null ? stored.getOriginalName() : "Attachment");
        attachment.setCreatedAt(clock.instant());
        attachments.save(attachment);
        return toResponse(notice, false);
    }

    // --------------------------------------------------------------- members

    @Transactional(readOnly = true)
    public PageResponse<NoticeDtos.NoticeResponse> list(Notice.Category category, boolean unreadOnly, boolean includeDrafts,
                                                        Integer page, Integer size) {
        UUID societyId = access.currentSocietyId();
        UUID userId = access.currentUserId();
        var pageable = Pages.of(page, size);
        Page<Notice> result;
        if (access.hasPermission(societyId, Permission.NOTICE_MANAGE) && !unreadOnly) {
            result = notices.findForAdmin(societyId, includeDrafts ? null : Notice.Status.PUBLISHED, category, pageable);
        } else {
            if (includeDrafts) {
                throw new ApiException(ErrorCode.ACCESS_DENIED);
            }
            access.require(societyId, Permission.NOTICE_READ);
            Audience audience = audienceOf(societyId);
            result = notices.findVisible(societyId, userId, audience.resident(), audience.guard(),
                    Ids.orNil(audience.towerIds()), category, unreadOnly, pageable);
        }
        Set<UUID> read = readIds(userId, result.getContent().stream().map(Notice::getId).toList());
        return PageResponse.of(result, n -> toResponse(n, read.contains(n.getId())));
    }

    @Transactional(readOnly = true)
    public NoticeDtos.NoticeResponse get(UUID id) {
        Notice notice = requireVisible(id);
        return toResponse(notice, readIds(access.currentUserId(), List.of(id)).contains(id));
    }

    @Transactional
    public void markRead(UUID id) {
        Notice notice = requireVisible(id);
        jdbc.update("INSERT INTO notice_read_receipts (notice_id, user_id, read_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                notice.getId(), access.currentUserId(), Timestamp.from(clock.instant()));
    }

    /** Published notices visible to the caller, newest first (resident dashboard). */
    @Transactional(readOnly = true)
    public List<NoticeDtos.NoticeResponse> latest(UUID societyId, int limit) {
        Audience audience = audienceOf(societyId);
        UUID userId = access.currentUserId();
        List<Notice> page = notices.findVisible(societyId, userId, audience.resident(), audience.guard(),
                Ids.orNil(audience.towerIds()), null, false, Pages.of(0, limit)).getContent();
        Set<UUID> read = readIds(userId, page.stream().map(Notice::getId).toList());
        return page.stream().map(n -> toResponse(n, read.contains(n.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID societyId) {
        Audience audience = audienceOf(societyId);
        return notices.findVisible(societyId, access.currentUserId(), audience.resident(), audience.guard(),
                Ids.orNil(audience.towerIds()), null, true, Pages.of(0, 1)).getTotalElements();
    }

    // ---------------------------------------------------------------- helpers

    private record Audience(boolean resident, boolean guard, Collection<UUID> towerIds) {
    }

    private Audience audienceOf(UUID societyId) {
        UserAccess current = access.access();
        Set<UUID> towerIds = current.flatsIn(societyId).stream().map(UserAccess.FlatAccess::towerId).collect(Collectors.toSet());
        return new Audience(current.hasRole(societyId, Role.RESIDENT), current.hasRole(societyId, Role.GUARD), towerIds);
    }

    private Notice requireVisible(UUID id) {
        Notice notice = notices.findById(id).filter(n -> n.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.NOTICE_NOT_FOUND));
        access.ensureInScope(notice.getSocietyId(), ErrorCode.NOTICE_NOT_FOUND);
        if (access.hasPermission(notice.getSocietyId(), Permission.NOTICE_MANAGE)) {
            return notice;
        }
        Audience audience = audienceOf(notice.getSocietyId());
        boolean visible = notice.isPublished() && switch (notice.getAudience()) {
            case ALL -> true;
            case RESIDENTS -> audience.resident();
            case GUARDS -> audience.guard();
            case TOWERS -> audience.resident() && notice.getTowerIds().stream().anyMatch(audience.towerIds()::contains);
        };
        if (!visible) {
            throw new ApiException(ErrorCode.NOTICE_NOT_FOUND);
        }
        return notice;
    }

    private Notice requireForManage(UUID id) {
        Notice notice = notices.findById(id).filter(n -> n.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.NOTICE_NOT_FOUND));
        access.ensureInScope(notice.getSocietyId(), ErrorCode.NOTICE_NOT_FOUND);
        access.require(notice.getSocietyId(), Permission.NOTICE_MANAGE);
        return notice;
    }

    private void apply(Notice notice, NoticeDtos.NoticeRequest request) {
        notice.setTitle(request.title().trim());
        notice.setBody(request.body().trim());
        notice.setCategory(request.category());
        notice.setImportant(Boolean.TRUE.equals(request.important()));
        notice.setAudience(request.audience() == null ? Notice.Audience.RESIDENTS : request.audience());
        String postedBy = Strings.trimToNull(request.postedBy());
        notice.setPostedByLabel(postedBy == null ? "Society Office" : postedBy);
        Set<UUID> towerIds = request.towerIds() == null ? Set.of() : new LinkedHashSet<>(request.towerIds());
        if (notice.getAudience() == Notice.Audience.TOWERS) {
            if (towerIds.isEmpty()) {
                throw ApiException.field("towerIds", "Select at least one tower for a tower notice");
            }
            List<Tower> found = towers.findAllById(towerIds);
            if (found.size() != towerIds.size() || found.stream().anyMatch(t -> !t.getSocietyId().equals(notice.getSocietyId()))) {
                throw ApiException.field("towerIds", "Select towers of this society");
            }
            notice.getTowerIds().clear();
            notice.getTowerIds().addAll(towerIds);
        } else {
            notice.getTowerIds().clear();
        }
    }

    private void publishInternal(Notice notice) {
        notice.setStatus(Notice.Status.PUBLISHED);
        notice.setPublishedAt(clock.instant());
        notices.flush();
        audit.record(notice.getSocietyId(), "NOTICE_PUBLISHED", "NOTICE", notice.getId(),
                "Notice \"" + notice.getTitle() + "\" published", Map.of("audience", notice.getAudience().name()));
        String firstLine = notice.getBody().split("\\R", 2)[0];
        notifications.notifyUsers(recipients(notice), notice.getSocietyId(), NotificationType.ANNOUNCEMENT,
                notice.isImportant() ? "Important: " + notice.getTitle() : notice.getTitle(), firstLine,
                Notification.ReferenceType.NOTICE, notice.getId());
    }

    private Collection<UUID> recipients(Notice notice) {
        Set<UUID> userIds = new HashSet<>();
        UUID societyId = notice.getSocietyId();
        switch (notice.getAudience()) {
            case ALL -> {
                userIds.addAll(activeMembers(societyId, Role.RESIDENT));
                userIds.addAll(activeMembers(societyId, Role.GUARD));
            }
            case RESIDENTS -> userIds.addAll(activeMembers(societyId, Role.RESIDENT));
            case GUARDS -> userIds.addAll(activeMembers(societyId, Role.GUARD));
            case TOWERS -> userIds.addAll(flatMemberships.findActiveResidentUserIdsInTowers(notice.getTowerIds()));
        }
        return userIds;
    }

    private List<UUID> activeMembers(UUID societyId, Role role) {
        return memberships.findBySocietyIdAndRoleAndStatus(societyId, role, SocietyMembership.Status.ACTIVE).stream()
                .map(SocietyMembership::getUserId).toList();
    }

    private Set<UUID> readIds(UUID userId, List<UUID> noticeIds) {
        return noticeIds.isEmpty() ? Set.of() : new HashSet<>(receipts.findReadNoticeIds(userId, noticeIds));
    }

    private NoticeDtos.NoticeResponse toResponse(Notice n, boolean read) {
        List<NoticeDtos.AttachmentResponse> files = new ArrayList<>();
        for (NoticeAttachment a : attachments.findByNoticeIdInOrderByCreatedAt(List.of(n.getId()))) {
            String size = this.files.find(a.getFileId()).map(f -> FileService.humanSize(f.getSizeBytes())).orElse(null);
            files.add(new NoticeDtos.AttachmentResponse(a.getId(), a.getDisplayName(), this.files.signedUrl(a.getFileId()), size));
        }
        return new NoticeDtos.NoticeResponse(n.getId(), n.getTitle(), n.getBody(), n.getCategory(), n.getPublishedAt(),
                n.getPostedByLabel(), n.isImportant(), read, audienceLabel(n), n.getAudience(), Set.copyOf(n.getTowerIds()),
                n.getStatus(), files, n.getCreatedAt(), n.getUpdatedAt());
    }

    private String audienceLabel(Notice n) {
        return switch (n.getAudience()) {
            case ALL -> "Everyone";
            case RESIDENTS -> "All residents";
            case GUARDS -> "Security staff";
            case TOWERS -> towers.findAllById(n.getTowerIds()).stream().map(Tower::getName).sorted()
                    .collect(Collectors.joining(", "));
        };
    }
}
