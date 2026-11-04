package com.mygateapp.complaints.service;

import com.mygateapp.common.Ids;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.common.dto.Refs;
import com.mygateapp.complaints.dto.ComplaintDtos;
import com.mygateapp.complaints.entity.Complaint;
import com.mygateapp.complaints.entity.ComplaintComment;
import com.mygateapp.complaints.entity.ComplaintStatusHistory;
import com.mygateapp.complaints.repository.ComplaintCommentRepository;
import com.mygateapp.complaints.repository.ComplaintRepository;
import com.mygateapp.complaints.repository.ComplaintStatusHistoryRepository;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.files.entity.StoredFile;
import com.mygateapp.files.service.FileService;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.flats.repository.FlatRepository;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.notifications.enums.NotificationType;
import com.mygateapp.notifications.service.NotificationService;
import com.mygateapp.residents.repository.FlatMembershipRepository;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.Permission;
import com.mygateapp.security.Role;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.society.repository.SocietyMembershipRepository;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Complaints raised by residents for their flats and handled by society admins.
 * Status flow: OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED (RESOLVED -> IN_PROGRESS reopens). Every change is recorded in
 * the immutable status history and notified to the flat's residents.
 */
@Service
public class ComplaintService {

    private static final Set<String> SORT_FIELDS = Set.of("updatedAt", "createdAt", "status");
    private static final long FIRST_TICKET = 1001;

    private final ComplaintRepository complaints;
    private final ComplaintCommentRepository comments;
    private final ComplaintStatusHistoryRepository history;
    private final FlatRepository flats;
    private final UserRepository users;
    private final FlatMembershipRepository flatMemberships;
    private final SocietyMembershipRepository societyMemberships;
    private final FileService files;
    private final NotificationService notifications;
    private final AccessService access;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ComplaintService(ComplaintRepository complaints, ComplaintCommentRepository comments, ComplaintStatusHistoryRepository history,
                            FlatRepository flats, UserRepository users, FlatMembershipRepository flatMemberships,
                            SocietyMembershipRepository societyMemberships, FileService files,
                            NotificationService notifications, AccessService access, JdbcTemplate jdbc, Clock clock) {
        this.complaints = complaints;
        this.comments = comments;
        this.history = history;
        this.flats = flats;
        this.users = users;
        this.flatMemberships = flatMemberships;
        this.societyMemberships = societyMemberships;
        this.files = files;
        this.notifications = notifications;
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ComplaintDtos.ComplaintResponse create(ComplaintDtos.CreateComplaintRequest request, MultipartFile image) {
        access.requireResidentFlat(request.flatId(), Permission.COMPLAINT_CREATE);
        Flat flat = flats.findById(request.flatId()).orElseThrow(() -> new ApiException(ErrorCode.FLAT_NOT_FOUND));
        User actor = currentUser();
        Complaint complaint = new Complaint();
        complaint.setSocietyId(flat.getSocietyId());
        complaint.setFlat(flat);
        complaint.setRaisedBy(actor);
        complaint.setTicketNumber("CMP-" + nextTicketNumber(flat.getSocietyId()));
        complaint.setCategory(request.category());
        complaint.setTitle(request.title().trim());
        complaint.setDescription(request.description().trim());
        if (image != null && !image.isEmpty()) {
            StoredFile stored = files.storeImage(image, StoredFile.Purpose.COMPLAINT_ATTACHMENT, flat.getSocietyId(), actor.getId());
            complaint.getAttachmentFileIds().add(stored.getId());
        }
        complaints.save(complaint);
        recordHistory(complaint, null, Complaint.Status.OPEN, "Complaint raised", null, actor);
        notifications.notifyUsers(admins(complaint.getSocietyId()), complaint.getSocietyId(), NotificationType.GENERAL,
                "New complaint " + complaint.getTicketNumber(), complaint.getTitle() + " · " + flat.toRef().label(),
                Notification.ReferenceType.COMPLAINT, complaint.getId());
        return toResponse(complaint, true);
    }

    @Transactional(readOnly = true)
    public PageResponse<ComplaintDtos.ComplaintResponse> list(Complaint.Status status, Complaint.Category category,
                                                              UUID flatId, String q, Integer page, Integer size, String sort) {
        UUID societyId = access.currentSocietyId();
        boolean admin = access.hasPermission(societyId, Permission.COMPLAINT_MANAGE);
        Collection<UUID> flatIds = List.of();
        if (!admin) {
            if (!access.hasPermission(societyId, Permission.COMPLAINT_VIEW_OWN)) {
                throw new ApiException(ErrorCode.ACCESS_DENIED);
            }
            flatIds = access.access().flatIdsIn(societyId);
            if (flatIds.isEmpty()) {
                return PageResponse.of(List.of(), 0, Pages.of(page, size).getPageSize(), 0);
            }
        }
        var pageable = Pages.of(page, size, sort, SORT_FIELDS, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return PageResponse.of(complaints.search(societyId, !admin, Ids.orNil(flatIds), status, category, flatId,
                Strings.likePattern(q), pageable), c -> toResponse(c, false));
    }

    @Transactional(readOnly = true)
    public ComplaintDtos.ComplaintResponse get(UUID id) {
        Complaint complaint = complaints.findById(id).orElseThrow(() -> new ApiException(ErrorCode.COMPLAINT_NOT_FOUND));
        requireVisible(complaint);
        return toResponse(complaint, true);
    }

    @Transactional
    public ComplaintDtos.ComplaintResponse update(UUID id, ComplaintDtos.UpdateComplaintRequest request) {
        Complaint complaint = lock(id);
        boolean admin = access.hasPermission(complaint.getSocietyId(), Permission.COMPLAINT_MANAGE);
        if (!admin) {
            requireVisible(complaint);
            if (complaint.getStatus() != Complaint.Status.OPEN) {
                throw new ApiException(ErrorCode.COMPLAINT_NOT_EDITABLE);
            }
            if (request.assignedToUserId() != null || request.adminRemarks() != null) {
                throw new ApiException(ErrorCode.ACCESS_DENIED, "Only society administrators can assign complaints.");
            }
        }
        if (request.category() != null) {
            complaint.setCategory(request.category());
        }
        if (request.title() != null) {
            complaint.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            complaint.setDescription(request.description().trim());
        }
        if (admin && request.adminRemarks() != null) {
            complaint.setAdminRemarks(Strings.trimToNull(request.adminRemarks()));
        }
        if (admin && request.assignedToUserId() != null) {
            boolean isAdmin = societyMemberships.findBySocietyIdAndUserIdAndRole(complaint.getSocietyId(),
                            request.assignedToUserId(), Role.SOCIETY_ADMIN)
                    .filter(m -> m.getStatus() == SocietyMembership.Status.ACTIVE).isPresent();
            if (!isAdmin) {
                throw ApiException.field(ErrorCode.INVALID_ASSIGNEE, "assignedToUserId", ErrorCode.INVALID_ASSIGNEE.defaultMessage());
            }
            User assignee = users.findById(request.assignedToUserId()).orElseThrow(() -> new ApiException(ErrorCode.INVALID_ASSIGNEE));
            complaint.setAssignedTo(assignee);
            recordHistory(complaint, complaint.getStatus(), complaint.getStatus(), "Assigned to " + assignee.displayName(),
                    null, currentUser());
        }
        return toResponse(complaint, true);
    }

    /** Admins move complaints through the workflow; a resident may only close their own RESOLVED complaint. */
    @Transactional
    public ComplaintDtos.ComplaintResponse changeStatus(UUID id, ComplaintDtos.StatusChangeRequest request) {
        Complaint complaint = lock(id);
        boolean admin = access.hasPermission(complaint.getSocietyId(), Permission.COMPLAINT_MANAGE);
        if (!admin) {
            requireVisible(complaint);
            if (!(complaint.getStatus() == Complaint.Status.RESOLVED && request.status() == Complaint.Status.CLOSED)) {
                throw new ApiException(ErrorCode.ACCESS_DENIED, "Residents can only close a resolved complaint.");
            }
        }
        Complaint.Status from = complaint.getStatus();
        if (!Complaint.canTransition(from, request.status())) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "A complaint cannot move from " + from.label() + " to " + request.status().label() + ".");
        }
        Instant now = clock.instant();
        complaint.setStatus(request.status());
        switch (request.status()) {
            case RESOLVED -> complaint.setResolvedAt(now);
            case CLOSED -> complaint.setClosedAt(now);
            case IN_PROGRESS, OPEN -> {
                complaint.setResolvedAt(null);
            }
        }
        String remarks = Strings.trimToNull(request.remarks());
        if (admin && remarks != null) {
            complaint.setAdminRemarks(remarks);
        }
        recordHistory(complaint, from, request.status(), request.status().label(), remarks, currentUser());
        if (admin) {
            notifications.notifyUsers(flatMemberships.findActiveResidentUserIds(List.of(complaint.getFlat().getId())),
                    complaint.getSocietyId(), NotificationType.COMPLAINT_UPDATE,
                    "Complaint " + complaint.getTicketNumber() + " is " + request.status().label().toLowerCase(),
                    complaint.getTitle(), Notification.ReferenceType.COMPLAINT, complaint.getId());
        }
        return toResponse(complaint, true);
    }

    @Transactional
    public ComplaintDtos.CommentResponse addComment(UUID id, ComplaintDtos.CommentRequest request) {
        Complaint complaint = lock(id);
        boolean admin = access.hasPermission(complaint.getSocietyId(), Permission.COMPLAINT_MANAGE);
        if (!admin) {
            requireVisible(complaint);
        }
        User actor = currentUser();
        ComplaintComment comment = new ComplaintComment();
        comment.setComplaintId(complaint.getId());
        comment.setAuthorId(actor.getId());
        comment.setAuthorName(actor.displayName());
        comment.setBody(request.body().trim());
        comment.setInternal(admin && Boolean.TRUE.equals(request.internal()));
        comment.setCreatedAt(clock.instant());
        comments.save(comment);
        if (admin && !comment.isInternal()) {
            notifications.notifyUsers(flatMemberships.findActiveResidentUserIds(List.of(complaint.getFlat().getId())),
                    complaint.getSocietyId(), NotificationType.COMPLAINT_UPDATE,
                    "New reply on " + complaint.getTicketNumber(), comment.getBody(),
                    Notification.ReferenceType.COMPLAINT, complaint.getId());
        } else if (!admin) {
            notifications.notifyUsers(admins(complaint.getSocietyId()), complaint.getSocietyId(), NotificationType.GENERAL,
                    "New comment on " + complaint.getTicketNumber(), comment.getBody(),
                    Notification.ReferenceType.COMPLAINT, complaint.getId());
        }
        return new ComplaintDtos.CommentResponse(comment.getId(), comment.getAuthorName(), comment.getBody(),
                comment.isInternal(), comment.getCreatedAt());
    }

    // ------------------------------------------------------------------ helpers

    private Complaint lock(UUID id) {
        Complaint complaint = complaints.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.COMPLAINT_NOT_FOUND));
        access.ensureInScope(complaint.getSocietyId(), ErrorCode.COMPLAINT_NOT_FOUND);
        return complaint;
    }

    /** Admins of the society, or residents of the complaint's flat. */
    private void requireVisible(Complaint complaint) {
        access.ensureInScope(complaint.getSocietyId(), ErrorCode.COMPLAINT_NOT_FOUND);
        if (access.hasPermission(complaint.getSocietyId(), Permission.COMPLAINT_MANAGE)) {
            return;
        }
        if (!access.isResidentOf(complaint.getFlat().getId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "This complaint belongs to another flat.");
        }
        access.requireResidentFlat(complaint.getFlat().getId(), Permission.COMPLAINT_VIEW_OWN);
    }

    /** Atomic per-society ticket sequence (CMP-1001, CMP-1002, ...). */
    private long nextTicketNumber(UUID societyId) {
        Long value = jdbc.queryForObject("""
                INSERT INTO society_counters (society_id, counter_name, current_value) VALUES (?, 'COMPLAINT', ?)
                ON CONFLICT (society_id, counter_name) DO UPDATE SET current_value = society_counters.current_value + 1
                RETURNING current_value
                """, Long.class, societyId, FIRST_TICKET);
        return value == null ? FIRST_TICKET : value;
    }

    private void recordHistory(Complaint complaint, Complaint.Status from, Complaint.Status to, String title,
                               String remarks, User actor) {
        ComplaintStatusHistory entry = new ComplaintStatusHistory();
        entry.setComplaintId(complaint.getId());
        entry.setFromStatus(from);
        entry.setToStatus(to);
        entry.setTitle(title);
        entry.setRemarks(remarks);
        entry.setChangedBy(actor == null ? null : actor.getId());
        entry.setChangedByName(actor == null ? null : actor.displayName());
        entry.setChangedAt(clock.instant());
        history.save(entry);
    }

    private List<UUID> admins(UUID societyId) {
        return societyMemberships.findBySocietyIdAndRoleAndStatus(societyId, Role.SOCIETY_ADMIN, SocietyMembership.Status.ACTIVE)
                .stream().map(SocietyMembership::getUserId).toList();
    }

    private User currentUser() {
        return users.findById(access.currentUserId()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    private ComplaintDtos.ComplaintResponse toResponse(Complaint c, boolean details) {
        boolean admin = access.hasPermission(c.getSocietyId(), Permission.COMPLAINT_MANAGE);
        List<Refs.TimelineEvent> timeline = details
                ? history.findByComplaintIdOrderByChangedAtAsc(c.getId()).stream().map(ComplaintStatusHistory::toTimeline).toList()
                : List.of();
        List<ComplaintDtos.CommentResponse> commentList = details
                ? comments.findByComplaintIdOrderByCreatedAtAsc(c.getId()).stream()
                .filter(cm -> admin || !cm.isInternal())
                .map(cm -> new ComplaintDtos.CommentResponse(cm.getId(), cm.getAuthorName(), cm.getBody(), cm.isInternal(),
                        cm.getCreatedAt()))
                .toList()
                : List.of();
        return new ComplaintDtos.ComplaintResponse(c.getId(), c.getTicketNumber(), c.getCategory(), c.getTitle(),
                c.getDescription(), c.getAttachmentFileIds().stream().map(files::signedUrl).toList(), c.getCreatedAt(),
                c.getUpdatedAt(), c.getStatus(), c.getAdminRemarks(), c.getFlat().toRef(),
                new Refs.UserRef(c.getRaisedBy().getId(), c.getRaisedBy().displayName()),
                c.getAssignedTo() == null ? null : new Refs.UserRef(c.getAssignedTo().getId(), c.getAssignedTo().displayName()),
                c.getResolvedAt(), c.getClosedAt(), timeline, commentList);
    }
}
