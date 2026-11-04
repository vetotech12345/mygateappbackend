package com.mygateapp.domesticstaff.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.Ids;
import com.mygateapp.common.MobileNumbers;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.TimeRanges;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.common.dto.Refs;
import com.mygateapp.common.dto.StatusRequest;
import com.mygateapp.domesticstaff.dto.StaffDtos;
import com.mygateapp.domesticstaff.entity.DomesticStaff;
import com.mygateapp.domesticstaff.entity.StaffAttendance;
import com.mygateapp.domesticstaff.entity.StaffFlatAssignment;
import com.mygateapp.domesticstaff.repository.StaffAssignmentRepository;
import com.mygateapp.domesticstaff.repository.StaffAttendanceRepository;
import com.mygateapp.domesticstaff.repository.StaffRepository;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.files.entity.StoredFile;
import com.mygateapp.files.service.FileService;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.flats.repository.FlatRepository;
import com.mygateapp.guards.entity.SocietyGate;
import com.mygateapp.guards.repository.GateRepository;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.notifications.enums.NotificationType;
import com.mygateapp.notifications.service.NotificationService;
import com.mygateapp.residents.repository.FlatMembershipRepository;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.GuardPermission;
import com.mygateapp.security.Permission;
import com.mygateapp.security.UserAccess;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Domestic staff registration (admins), visibility (residents of assigned flats, guards) and gate attendance. */
@Service
public class DomesticStaffService {

    private final StaffRepository staffRepository;
    private final StaffAssignmentRepository assignments;
    private final StaffAttendanceRepository attendance;
    private final FlatRepository flats;
    private final GateRepository gates;
    private final UserRepository users;
    private final FlatMembershipRepository flatMemberships;
    private final FileService files;
    private final NotificationService notifications;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public DomesticStaffService(StaffRepository staffRepository, StaffAssignmentRepository assignments,
                                StaffAttendanceRepository attendance, FlatRepository flats, GateRepository gates,
                                UserRepository users, FlatMembershipRepository flatMemberships, FileService files,
                                NotificationService notifications, AccessService access, AuditService audit, Clock clock) {
        this.staffRepository = staffRepository;
        this.assignments = assignments;
        this.attendance = attendance;
        this.flats = flats;
        this.gates = gates;
        this.users = users;
        this.flatMemberships = flatMemberships;
        this.files = files;
        this.notifications = notifications;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------- admin CRUD

    @Transactional
    public StaffDtos.StaffResponse create(StaffDtos.StaffRequest request) {
        UUID societyId = access.resolveSocietyId(request.societyId());
        access.require(societyId, Permission.STAFF_MANAGE);
        String mobile = MobileNumbers.normalize(MobileNumbers.DEFAULT_COUNTRY_CODE, request.mobile(), "mobile");
        if (staffRepository.existsBySocietyIdAndMobile(societyId, mobile)) {
            throw ApiException.field(ErrorCode.STAFF_MOBILE_TAKEN, "mobile", ErrorCode.STAFF_MOBILE_TAKEN.defaultMessage());
        }
        String pass = Strings.trimToNull(request.passNumber());
        if (pass != null && staffRepository.existsBySocietyIdAndPassNumber(societyId, pass)) {
            throw ApiException.field(ErrorCode.STAFF_PASS_TAKEN, "passNumber", ErrorCode.STAFF_PASS_TAKEN.defaultMessage());
        }
        DomesticStaff staff = new DomesticStaff();
        staff.setSocietyId(societyId);
        staff.setName(request.name().trim());
        staff.setMobile(mobile);
        staff.setCategory(request.category());
        staff.setPassNumber(pass);
        staff.setWorkingSince(request.workingSince());
        staff.setCreatedBy(access.currentUserId());
        staffRepository.save(staff);
        replaceAssignments(staff, request.flatIds());
        audit.record(societyId, "STAFF_CREATED", "DOMESTIC_STAFF", staff.getId(),
                staff.getName() + " registered as " + staff.getCategory().label(),
                Map.of("flats", request.flatIds() == null ? 0 : request.flatIds().size()));
        return toResponse(staff, activeFlats(List.of(staff.getId())));
    }

    @Transactional
    public StaffDtos.StaffResponse update(UUID id, StaffDtos.StaffRequest request) {
        DomesticStaff staff = requireStaff(id);
        access.require(staff.getSocietyId(), Permission.STAFF_MANAGE);
        String mobile = MobileNumbers.normalize(MobileNumbers.DEFAULT_COUNTRY_CODE, request.mobile(), "mobile");
        if (!mobile.equals(staff.getMobile()) && staffRepository.existsBySocietyIdAndMobile(staff.getSocietyId(), mobile)) {
            throw ApiException.field(ErrorCode.STAFF_MOBILE_TAKEN, "mobile", ErrorCode.STAFF_MOBILE_TAKEN.defaultMessage());
        }
        String pass = Strings.trimToNull(request.passNumber());
        if (pass != null && !pass.equals(staff.getPassNumber())
                && staffRepository.existsBySocietyIdAndPassNumber(staff.getSocietyId(), pass)) {
            throw ApiException.field(ErrorCode.STAFF_PASS_TAKEN, "passNumber", ErrorCode.STAFF_PASS_TAKEN.defaultMessage());
        }
        staff.setName(request.name().trim());
        staff.setMobile(mobile);
        staff.setCategory(request.category());
        staff.setPassNumber(pass);
        staff.setWorkingSince(request.workingSince());
        if (request.flatIds() != null) {
            replaceAssignments(staff, request.flatIds());
        }
        audit.record(staff.getSocietyId(), "STAFF_UPDATED", "DOMESTIC_STAFF", id, staff.getName() + " updated", Map.of());
        return toResponse(staff, activeFlats(List.of(id)));
    }

    @Transactional
    public StaffDtos.StaffResponse updateStatus(UUID id, StatusRequest request) {
        DomesticStaff staff = requireStaff(id);
        access.require(staff.getSocietyId(), Permission.STAFF_MANAGE);
        ActiveStatus previous = staff.getStatus();
        staff.setStatus(request.status());
        audit.record(staff.getSocietyId(), "STAFF_STATUS_CHANGED", "DOMESTIC_STAFF", id,
                staff.getName() + " " + previous + " -> " + request.status(),
                Map.of("reason", request.reason() == null ? "" : request.reason()));
        return toResponse(staff, activeFlats(List.of(id)));
    }

    @Transactional
    public StaffDtos.StaffResponse uploadPhoto(UUID id, MultipartFile photo) {
        DomesticStaff staff = requireStaff(id);
        access.require(staff.getSocietyId(), Permission.STAFF_MANAGE);
        UUID previous = staff.getPhotoFileId();
        StoredFile stored = files.storeImage(photo, StoredFile.Purpose.STAFF_PHOTO, staff.getSocietyId(), access.currentUserId());
        staff.setPhotoFileId(stored.getId());
        staffRepository.flush();
        if (previous != null) {
            files.delete(previous);
        }
        return toResponse(staff, activeFlats(List.of(id)));
    }

    // ------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public PageResponse<StaffDtos.StaffResponse> list(String q, DomesticStaff.Presence presence,
                                                      DomesticStaff.Category category, Integer page, Integer size) {
        UUID societyId = access.currentSocietyId();
        Visibility visibility = visibility(societyId);
        Page<DomesticStaff> result = staffRepository.search(societyId, visibility.restricted(), Ids.orNil(visibility.flatIds()),
                visibility.admin() ? null : ActiveStatus.ACTIVE,
                presence == null ? null : presence == DomesticStaff.Presence.INSIDE,
                category, Strings.likePattern(q), Pages.of(page, size));
        Map<UUID, List<Refs.FlatRef>> flatsByStaff = activeFlats(result.getContent().stream().map(DomesticStaff::getId).toList());
        return PageResponse.of(result, s -> toResponse(s, flatsByStaff));
    }

    @Transactional(readOnly = true)
    public StaffDtos.StaffResponse get(UUID id) {
        DomesticStaff staff = requireStaff(id);
        requireVisible(staff);
        return toResponse(staff, activeFlats(List.of(id)));
    }

    @Transactional(readOnly = true)
    public PageResponse<StaffDtos.AttendanceResponse> attendanceHistory(UUID staffId, Instant from, Integer page, Integer size) {
        UUID societyId;
        Visibility visibility;
        if (staffId != null) {
            DomesticStaff staff = requireStaff(staffId);
            visibility = requireVisible(staff);
            societyId = staff.getSocietyId();
        } else {
            societyId = access.currentSocietyId();
            visibility = visibility(societyId);
        }
        Page<StaffAttendance> result = attendance.search(societyId, staffId, TimeRanges.orMin(from), visibility.restricted(),
                Ids.orNil(visibility.flatIds()), Pages.of(page, size));
        Set<UUID> userIds = new HashSet<>();
        Set<UUID> gateIds = new HashSet<>();
        result.forEach(a -> {
            userIds.add(a.getCheckedInBy());
            userIds.add(a.getCheckedOutBy());
            gateIds.add(a.getCheckInGateId());
        });
        userIds.remove(null);
        gateIds.remove(null);
        Map<UUID, String> names = users.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::displayName));
        Map<UUID, SocietyGate> gateById = gates.findAllById(gateIds).stream()
                .collect(Collectors.toMap(SocietyGate::getId, Function.identity()));
        return PageResponse.of(result, a -> new StaffDtos.AttendanceResponse(a.getId(), a.getStaff().getId(),
                a.getStaff().getName(), a.getStaff().getCategory(), a.getCheckInAt(), a.getCheckOutAt(),
                a.getCheckInGateId() == null || !gateById.containsKey(a.getCheckInGateId()) ? null
                        : gateById.get(a.getCheckInGateId()).toRef(),
                names.get(a.getCheckedInBy()), names.get(a.getCheckedOutBy())));
    }

    // ------------------------------------------------------------ guard actions

    @Transactional
    public StaffDtos.StaffResponse checkIn(UUID id) {
        UserAccess.GuardAccess guard = access.requireOnDutyGuard(GuardPermission.STAFF_ATTENDANCE);
        DomesticStaff staff = lockInSociety(id, guard.societyId());
        if (staff.getStatus() != ActiveStatus.ACTIVE) {
            throw new ApiException(ErrorCode.STAFF_INACTIVE);
        }
        if (staff.isInside() || attendance.findOpen(id).isPresent()) {
            throw new ApiException(ErrorCode.STAFF_ALREADY_INSIDE);
        }
        Instant now = clock.instant();
        StaffAttendance record = new StaffAttendance();
        record.setSocietyId(staff.getSocietyId());
        record.setStaff(staff);
        record.setCheckInAt(now);
        record.setCheckInGateId(guard.gateId());
        record.setCheckedInBy(access.currentUserId());
        attendance.save(record);
        staff.setInside(true);
        staff.setLastCheckInAt(now);

        Map<UUID, List<Refs.FlatRef>> assigned = activeFlats(List.of(id));
        List<UUID> flatIds = assigned.getOrDefault(id, List.of()).stream().map(Refs.FlatRef::id).toList();
        String gateName = gates.findById(guard.gateId()).map(SocietyGate::getName).orElse("the gate");
        if (!flatIds.isEmpty()) {
            notifications.notifyUsers(flatMemberships.findActiveResidentUserIds(flatIds), staff.getSocietyId(),
                    NotificationType.DOMESTIC_HELP_ARRIVAL, staff.getName() + " has arrived",
                    staff.getCategory().label() + " checked in at " + gateName + ".",
                    Notification.ReferenceType.STAFF, staff.getId());
        }
        return toResponse(staff, assigned);
    }

    @Transactional
    public StaffDtos.StaffResponse checkOut(UUID id) {
        UserAccess.GuardAccess guard = access.requireOnDutyGuard(GuardPermission.STAFF_ATTENDANCE);
        DomesticStaff staff = lockInSociety(id, guard.societyId());
        StaffAttendance open = attendance.findOpen(id).orElse(null);
        if (!staff.isInside() || open == null) {
            throw new ApiException(ErrorCode.STAFF_NOT_INSIDE);
        }
        Instant now = clock.instant();
        open.setCheckOutAt(now.isBefore(open.getCheckInAt()) ? open.getCheckInAt() : now);
        open.setCheckOutGateId(guard.gateId());
        open.setCheckedOutBy(access.currentUserId());
        staff.setInside(false);
        staff.setLastCheckOutAt(open.getCheckOutAt());
        return toResponse(staff, activeFlats(List.of(id)));
    }

    // ------------------------------------------------------------------ helpers

    private record Visibility(boolean admin, boolean restricted, Collection<UUID> flatIds) {
    }

    /** Admins/guards see the whole society; residents only staff assigned to their flats. */
    private Visibility visibility(UUID societyId) {
        if (access.hasPermission(societyId, Permission.STAFF_VIEW)) {
            return new Visibility(true, false, List.of());
        }
        if (access.hasPermission(societyId, Permission.GATE_LOG_VIEW)) {
            return new Visibility(false, false, List.of());
        }
        if (access.hasPermission(societyId, Permission.STAFF_VIEW_OWN)) {
            return new Visibility(false, true, access.access().flatIdsIn(societyId));
        }
        throw new ApiException(ErrorCode.ACCESS_DENIED);
    }

    private Visibility requireVisible(DomesticStaff staff) {
        Visibility visibility = visibility(staff.getSocietyId());
        if (visibility.restricted()
                && (visibility.flatIds().isEmpty() || !assignments.isAssignedToAny(staff.getId(), visibility.flatIds()))) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "This staff member is not assigned to your flat.");
        }
        return visibility;
    }

    private void replaceAssignments(DomesticStaff staff, List<UUID> flatIds) {
        Set<UUID> wanted = flatIds == null ? Set.of() : new LinkedHashSet<>(flatIds);
        for (UUID flatId : wanted) {
            Flat flat = flats.findById(flatId).orElse(null);
            if (flat == null || !flat.getSocietyId().equals(staff.getSocietyId()) || !flat.isActive()) {
                throw ApiException.field(ErrorCode.INVALID_FLAT, "flatIds", "Select active flats of the same society.");
            }
        }
        Instant now = clock.instant();
        List<StaffFlatAssignment> current = assignments.findActiveForStaff(List.of(staff.getId()));
        Set<UUID> kept = new HashSet<>();
        for (StaffFlatAssignment assignment : current) {
            if (wanted.contains(assignment.getFlat().getId())) {
                kept.add(assignment.getFlat().getId());
            } else {
                assignment.setActive(false);
                assignment.setEndedAt(now);
            }
        }
        assignments.flush();
        for (UUID flatId : wanted) {
            if (!kept.contains(flatId)) {
                StaffFlatAssignment assignment = new StaffFlatAssignment();
                assignment.setSocietyId(staff.getSocietyId());
                assignment.setStaffId(staff.getId());
                assignment.setFlat(flats.getReferenceById(flatId));
                assignment.setAssignedAt(now);
                assignments.save(assignment);
            }
        }
        assignments.flush();
    }

    private Map<UUID, List<Refs.FlatRef>> activeFlats(List<UUID> staffIds) {
        if (staffIds.isEmpty()) {
            return Map.of();
        }
        return assignments.findActiveForStaff(staffIds).stream()
                .collect(Collectors.groupingBy(StaffFlatAssignment::getStaffId,
                        Collectors.mapping(a -> a.getFlat().toRef(), Collectors.toList())));
    }

    private DomesticStaff requireStaff(UUID id) {
        DomesticStaff staff = staffRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.STAFF_NOT_FOUND));
        access.ensureInScope(staff.getSocietyId(), ErrorCode.STAFF_NOT_FOUND);
        return staff;
    }

    private DomesticStaff lockInSociety(UUID id, UUID societyId) {
        DomesticStaff staff = staffRepository.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.STAFF_NOT_FOUND));
        if (!Objects.equals(staff.getSocietyId(), societyId)) {
            throw new ApiException(ErrorCode.STAFF_NOT_FOUND);
        }
        return staff;
    }

    private StaffDtos.StaffResponse toResponse(DomesticStaff s, Map<UUID, List<Refs.FlatRef>> flatsByStaff) {
        return new StaffDtos.StaffResponse(s.getId(), s.getName(), s.getMobile(), s.getCategory(),
                files.signedUrl(s.getPhotoFileId()), new ArrayList<>(flatsByStaff.getOrDefault(s.getId(), List.of())),
                s.isInside(), s.getLastCheckInAt(), s.getLastCheckOutAt(), s.getWorkingSince(), s.getPassNumber(),
                s.getStatus());
    }
}
