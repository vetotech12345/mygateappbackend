package com.mygateapp.vehicle.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.Ids;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.common.dto.Refs.SocietyRef;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.flats.repository.FlatRepository;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.parking.entity.ParkingAllocation;
import com.mygateapp.parking.entity.ParkingPermit;
import com.mygateapp.parking.entity.SocietyParkingSettings;
import com.mygateapp.parking.enums.ParkingEnums.AllocationStatus;
import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.enums.ParkingEnums.SessionStatus;
import com.mygateapp.parking.repository.ParkingAllocationRepository;
import com.mygateapp.parking.repository.ParkingPermitRepository;
import com.mygateapp.parking.repository.ParkingSessionRepository;
import com.mygateapp.parking.service.ParkingAccess;
import com.mygateapp.parking.service.ParkingAllocationService;
import com.mygateapp.parking.service.ParkingRefLoader;
import com.mygateapp.parking.service.ParkingSettingsStore;
import com.mygateapp.parking.service.ParkingSupport;
import com.mygateapp.security.Permission;
import com.mygateapp.security.UserAccess;
import com.mygateapp.society.entity.Society;
import com.mygateapp.users.entity.User;
import com.mygateapp.vehicle.VehicleNumbers;
import com.mygateapp.vehicle.dto.VehicleDtos.CreateVehicleRequest;
import com.mygateapp.vehicle.dto.VehicleDtos.RegistrationView;
import com.mygateapp.vehicle.dto.VehicleDtos.UpdateVehicleRequest;
import com.mygateapp.vehicle.dto.VehicleDtos.VehicleResponse;
import com.mygateapp.vehicle.entity.Vehicle;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.NumberFormat;
import com.mygateapp.vehicle.enums.VehicleEnums.RegistrationStatus;
import com.mygateapp.vehicle.repository.VehicleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vehicle registry. Residents register vehicles of their own flats (ownership is claimed and verified by the society
 * office); admins register society, staff, service and emergency vehicles and approve, reject or suspend any vehicle.
 * Approval never grants gate access by itself.
 */
@Service
public class VehicleService {

    private static final Set<String> SORT_FIELDS = Set.of("registrationNumber", "createdAt", "updatedAt", "registrationStatus");

    private final VehicleRepository vehicles;
    private final ParkingAllocationRepository allocations;
    private final ParkingPermitRepository permits;
    private final ParkingSessionRepository sessions;
    private final FlatRepository flats;
    private final ParkingAllocationService allocationService;
    private final ParkingSettingsStore settingsStore;
    private final ParkingAccess access;
    private final ParkingSupport support;
    private final ParkingRefLoader refLoader;
    private final AuditService audit;
    private final Clock clock;

    public VehicleService(VehicleRepository vehicles, ParkingAllocationRepository allocations,
                          ParkingPermitRepository permits, ParkingSessionRepository sessions, FlatRepository flats,
                          ParkingAllocationService allocationService, ParkingSettingsStore settingsStore,
                          ParkingAccess access, ParkingSupport support, ParkingRefLoader refLoader, AuditService audit,
                          Clock clock) {
        this.vehicles = vehicles;
        this.allocations = allocations;
        this.permits = permits;
        this.sessions = sessions;
        this.flats = flats;
        this.allocationService = allocationService;
        this.settingsStore = settingsStore;
        this.access = access;
        this.support = support;
        this.refLoader = refLoader;
        this.audit = audit;
        this.clock = clock;
    }

    // ================================================================= create

    @Transactional
    public VehicleResponse create(CreateVehicleRequest request) {
        UUID societyId = access.societyId();
        boolean admin = access.core().hasPermission(societyId, Permission.VEHICLE_MANAGE);
        Category category;
        UUID flatId = request.flatId();
        User actor = support.user(access.currentUserId());
        if (admin) {
            category = request.category() != null ? request.category() : flatId != null ? Category.RESIDENT : Category.SOCIETY;
            if (category == Category.RESIDENT && flatId == null) {
                throw ApiException.field("flatId", "Resident vehicles belong to a flat.");
            }
            if (flatId != null) {
                requireFlat(flatId, societyId);
            }
        } else {
            if (request.category() != null && request.category() != Category.RESIDENT) {
                throw new ApiException(ErrorCode.ACCESS_DENIED, "Residents can only register their own vehicles.");
            }
            if (flatId == null) {
                throw ApiException.field("flatId", "Select your flat.");
            }
            category = Category.RESIDENT;
            UserAccess.FlatAccess flat = access.requireResidentFlat(flatId, Permission.VEHICLE_REGISTER_OWN);
            societyId = flat.societyId();
        }
        SocietyParkingSettings settings = settingsStore.settingsFor(societyId);
        NumberFormat format = request.numberFormat() != null ? request.numberFormat() : settings.getVehicleNumberFormat();
        String number = VehicleNumbers.requireValid(request.registrationNumber(), format, "registrationNumber");
        ParkingSettingsStore.requireAllowedType(settings, request.vehicleType(), "vehicleType");
        requireNumberFree(societyId, number);
        if (category == Category.RESIDENT && vehicles.countByFlatIdAndActiveTrue(flatId) >= settings.getMaxVehiclesPerFlat()) {
            throw new ApiException(ErrorCode.VEHICLE_LIMIT_REACHED,
                    "A flat can register at most " + settings.getMaxVehiclesPerFlat() + " vehicles.");
        }

        Instant now = clock.instant();
        Vehicle vehicle = new Vehicle();
        vehicle.setSocietyId(societyId);
        vehicle.setRegistrationNumber(number);
        vehicle.setVehicleType(request.vehicleType());
        vehicle.setCategory(category);
        vehicle.setMake(Strings.trimToNull(request.make()));
        vehicle.setModel(Strings.trimToNull(request.model()));
        vehicle.setColor(Strings.trimToNull(request.color()));
        vehicle.setFlatId(flatId);
        vehicle.setCreatedBy(actor.getId());
        if (admin) {
            vehicle.setOwnerName(Strings.trimToNull(request.ownerName()));
            vehicle.setRegistrationStatus(RegistrationStatus.APPROVED);
            vehicle.setSubmittedAt(now);
            vehicle.setReviewedAt(now);
            vehicle.setReviewedBy(actor.getId());
            vehicle.setReviewRemarks("Registered by the society office");
        } else {
            vehicle.setOwnerUserId(actor.getId());
            vehicle.setOwnerName(actor.getName());
            boolean submit = !Boolean.FALSE.equals(request.submitForApproval());
            vehicle.setRegistrationStatus(submit ? RegistrationStatus.PENDING : RegistrationStatus.NOT_SUBMITTED);
            vehicle.setSubmittedAt(submit ? now : null);
        }
        save(vehicle);
        audit.record(societyId, "VEHICLE_REGISTERED", "VEHICLE", vehicle.getId(),
                "Vehicle " + number + " registered (" + category + ")",
                Map.of("status", vehicle.getRegistrationStatus().name(), "type", vehicle.getVehicleType().name()));
        if (vehicle.getRegistrationStatus() == RegistrationStatus.PENDING) {
            support.notifyAdmins(societyId, "Vehicle waiting for approval",
                    number + " was registered by a resident and needs review.", Notification.ReferenceType.VEHICLE,
                    vehicle.getId());
        }
        return toResponse(vehicle, refLoader.refs(), admin);
    }

    // ================================================================= update

    @Transactional
    public VehicleResponse update(UUID id, UpdateVehicleRequest request) {
        Vehicle vehicle = lock(id);
        boolean admin = access.core().hasPermission(vehicle.getSocietyId(), Permission.VEHICLE_MANAGE);
        if (!admin) {
            requireOwnFlat(vehicle, Permission.VEHICLE_REGISTER_OWN);
            if (request.ownerName() != null) {
                throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the society office can change the owner name.");
            }
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        SocietyParkingSettings settings = settingsStore.settingsFor(vehicle.getSocietyId());

        if (request.active() != null && request.active() != vehicle.isActive()) {
            if (!request.active()) {
                deactivate(vehicle);
                changes.put("active", false);
            } else {
                requireNumberFree(vehicle.getSocietyId(), vehicle.getRegistrationNumber());
                if (vehicle.getFlatId() != null && vehicle.getCategory() == Category.RESIDENT
                        && vehicles.countByFlatIdAndActiveTrue(vehicle.getFlatId()) >= settings.getMaxVehiclesPerFlat()) {
                    throw new ApiException(ErrorCode.VEHICLE_LIMIT_REACHED);
                }
                vehicle.setActive(true);
                if (!admin) {
                    markPending(vehicle);
                }
                changes.put("active", true);
            }
        }
        if (!vehicle.isActive() && hasIdentityChange(vehicle, request)) {
            throw new ApiException(ErrorCode.VEHICLE_INACTIVE);
        }

        boolean identityChanged = false;
        if (request.registrationNumber() != null) {
            NumberFormat format = request.numberFormat() != null ? request.numberFormat() : settings.getVehicleNumberFormat();
            String number = VehicleNumbers.requireValid(request.registrationNumber(), format, "registrationNumber");
            if (!number.equals(vehicle.getRegistrationNumber())) {
                requireNotInside(vehicle);
                requireNumberFree(vehicle.getSocietyId(), number);
                changes.put("registrationNumber", vehicle.getRegistrationNumber() + " -> " + number);
                vehicle.setRegistrationNumber(number);
                identityChanged = true;
            }
        }
        if (request.vehicleType() != null && request.vehicleType() != vehicle.getVehicleType()) {
            ParkingSettingsStore.requireAllowedType(settings, request.vehicleType(), "vehicleType");
            changes.put("vehicleType", vehicle.getVehicleType() + " -> " + request.vehicleType());
            vehicle.setVehicleType(request.vehicleType());
            identityChanged = true;
        }
        if (request.make() != null) {
            vehicle.setMake(Strings.trimToNull(request.make()));
        }
        if (request.model() != null) {
            vehicle.setModel(Strings.trimToNull(request.model()));
        }
        if (request.color() != null) {
            vehicle.setColor(Strings.trimToNull(request.color()));
        }
        if (admin && request.ownerName() != null) {
            vehicle.setOwnerName(Strings.trimToNull(request.ownerName()));
        }
        if (identityChanged) {
            allocationService.renumberPermits(vehicle.getId(), vehicle.getRegistrationNumber(), vehicle.getVehicleType());
            if (!admin && vehicle.getRegistrationStatus() == RegistrationStatus.APPROVED) {
                // A changed number/type must be re-verified; its slot and permits wait for the new approval.
                markPending(vehicle);
                allocationService.suspendForVehicle(vehicle.getId());
                changes.put("registrationStatus", "PENDING (re-approval)");
                support.notifyAdmins(vehicle.getSocietyId(), "Vehicle changed: approval needed",
                        vehicle.getRegistrationNumber() + " was edited by a resident and needs review.",
                        Notification.ReferenceType.VEHICLE, vehicle.getId());
            }
        }
        save(vehicle);
        audit.record(vehicle.getSocietyId(), "VEHICLE_UPDATED", "VEHICLE", id,
                "Vehicle " + vehicle.getRegistrationNumber() + " updated", changes);
        return toResponse(vehicle, refLoader.refs(), admin);
    }

    @Transactional
    public VehicleResponse submit(UUID id) {
        Vehicle vehicle = lock(id);
        requireOwnFlat(vehicle, Permission.VEHICLE_REGISTER_OWN);
        if (!vehicle.isActive()) {
            throw new ApiException(ErrorCode.VEHICLE_INACTIVE);
        }
        if (vehicle.getRegistrationStatus() != RegistrationStatus.NOT_SUBMITTED
                && vehicle.getRegistrationStatus() != RegistrationStatus.REJECTED) {
            throw new ApiException(ErrorCode.VEHICLE_NOT_SUBMITTABLE);
        }
        markPending(vehicle);
        save(vehicle);
        audit.record(vehicle.getSocietyId(), "VEHICLE_SUBMITTED", "VEHICLE", id,
                "Vehicle " + vehicle.getRegistrationNumber() + " sent for approval", Map.of());
        support.notifyAdmins(vehicle.getSocietyId(), "Vehicle waiting for approval",
                vehicle.getRegistrationNumber() + " needs review.", Notification.ReferenceType.VEHICLE, id);
        return toResponse(vehicle, refLoader.refs(), false);
    }

    // ================================================================= review

    @Transactional
    public VehicleResponse approve(UUID id, String remarks) {
        Vehicle vehicle = lockForAdmin(id);
        if (!vehicle.isActive()) {
            throw new ApiException(ErrorCode.VEHICLE_INACTIVE);
        }
        if (vehicle.getRegistrationStatus() == RegistrationStatus.APPROVED) {
            throw new ApiException(ErrorCode.VEHICLE_NOT_PENDING, "This vehicle is already approved.");
        }
        RegistrationStatus previous = vehicle.getRegistrationStatus();
        review(vehicle, RegistrationStatus.APPROVED, remarks);
        allocationService.reinstateForVehicle(vehicle.getId());
        save(vehicle);
        audit.record(vehicle.getSocietyId(), "VEHICLE_APPROVED", "VEHICLE", id,
                "Vehicle " + vehicle.getRegistrationNumber() + " approved", Map.of("from", previous.name()));
        support.notifyFlat(vehicle.getFlatId(), vehicle.getSocietyId(), "Vehicle approved",
                vehicle.getRegistrationNumber() + " is now registered with the society.",
                Notification.ReferenceType.VEHICLE, id);
        return toResponse(vehicle, refLoader.refs(), true);
    }

    @Transactional
    public VehicleResponse reject(UUID id, String remarks) {
        Vehicle vehicle = lockForAdmin(id);
        if (vehicle.getRegistrationStatus() != RegistrationStatus.PENDING) {
            throw new ApiException(ErrorCode.VEHICLE_NOT_PENDING);
        }
        review(vehicle, RegistrationStatus.REJECTED, requireReason(remarks));
        allocationService.suspendForVehicle(vehicle.getId());
        save(vehicle);
        audit.record(vehicle.getSocietyId(), "VEHICLE_REJECTED", "VEHICLE", id,
                "Vehicle " + vehicle.getRegistrationNumber() + " rejected", Map.of("remarks", remarks));
        support.notifyFlat(vehicle.getFlatId(), vehicle.getSocietyId(), "Vehicle registration rejected",
                vehicle.getRegistrationNumber() + " was not approved. Open the app for the reason.",
                Notification.ReferenceType.VEHICLE, id);
        return toResponse(vehicle, refLoader.refs(), true);
    }

    @Transactional
    public VehicleResponse suspend(UUID id, String remarks) {
        Vehicle vehicle = lockForAdmin(id);
        if (vehicle.getRegistrationStatus() != RegistrationStatus.APPROVED) {
            throw new ApiException(ErrorCode.VEHICLE_NOT_APPROVED);
        }
        review(vehicle, RegistrationStatus.SUSPENDED, requireReason(remarks));
        allocationService.suspendForVehicle(vehicle.getId());
        save(vehicle);
        audit.record(vehicle.getSocietyId(), "VEHICLE_SUSPENDED", "VEHICLE", id,
                "Vehicle " + vehicle.getRegistrationNumber() + " suspended", Map.of("remarks", remarks));
        support.notifyFlat(vehicle.getFlatId(), vehicle.getSocietyId(), "Vehicle registration suspended",
                vehicle.getRegistrationNumber() + " has been suspended by the society office.",
                Notification.ReferenceType.VEHICLE, id);
        return toResponse(vehicle, refLoader.refs(), true);
    }

    // ================================================================== reads

    /** Residents: vehicles of their flats in the current society. Admins: all vehicles with filters. */
    @Transactional(readOnly = true)
    public PageResponse<VehicleResponse> list(String q, Category category, RegistrationStatus status, UUID flatId,
                                              Boolean active, Integer page, Integer size, String sort) {
        ParkingAccess.Scope scope = access.readScope(Permission.VEHICLE_MANAGE, false);
        Collection<UUID> flatIds = scope.resident() ? Ids.orNil(scope.flatIds()) : Ids.orNil(List.of());
        String like = Strings.likePattern(q);
        String numberLike = q == null || q.isBlank() ? null : "%" + VehicleNumbers.normalize(q).replace("%", "") + "%";
        Page<Vehicle> result = vehicles.search(scope.societyId(), scope.resident(), flatIds, flatId, category, status,
                active, like, numberLike == null ? like : numberLike,
                Pages.of(page, size, sort, SORT_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt")));
        ParkingRefLoader.Refs refs = refLoader.refs()
                .preloadFlats(result.getContent().stream().map(Vehicle::getFlatId).toList());
        Map<UUID, ParkingAllocation> slotByVehicle = liveAllocations(result.getContent());
        Map<UUID, ParkingPermit> permitByVehicle = openPermits(result.getContent());
        refs.preloadSlots(slotByVehicle.values().stream().map(ParkingAllocation::getSlotId).toList());
        return PageResponse.of(result, v -> toResponse(v, refs, scope.admin(), slotByVehicle.get(v.getId()),
                permitByVehicle.get(v.getId())));
    }

    @Transactional(readOnly = true)
    public VehicleResponse get(UUID id) {
        ParkingAccess.Scope scope = access.readScope(Permission.VEHICLE_MANAGE, false);
        Vehicle vehicle = vehicles.findById(id).orElseThrow(() -> new ApiException(ErrorCode.VEHICLE_NOT_FOUND));
        access.ensureSameSociety(vehicle.getSocietyId(), scope.societyId(), ErrorCode.VEHICLE_NOT_FOUND);
        if (!scope.canSeeFlat(vehicle.getFlatId())) {
            throw new ApiException(ErrorCode.VEHICLE_NOT_FOUND);
        }
        return toResponse(vehicle, refLoader.refs(), scope.admin());
    }

    // ================================================================ mapping

    public VehicleResponse toResponse(Vehicle v, ParkingRefLoader.Refs refs, boolean admin) {
        return toResponse(v, refs, admin, liveAllocations(List.of(v)).get(v.getId()), openPermits(List.of(v)).get(v.getId()));
    }

    private VehicleResponse toResponse(Vehicle v, ParkingRefLoader.Refs refs, boolean admin, ParkingAllocation allocation,
                                       ParkingPermit permit) {
        Society society = support.society(v.getSocietyId());
        String reviewer = v.getReviewedBy() == null ? null : admin ? refs.userName(v.getReviewedBy()) : "Society office";
        return new VehicleResponse(v.getId(), v.getRegistrationNumber(), v.getVehicleType(), v.getMake(), v.getModel(),
                v.getColor(), v.getCategory(), new SocietyRef(society.getId(), society.getName(), society.getCity()),
                refs.flat(v.getFlatId()), v.getOwnerName(), v.isActive(),
                new RegistrationView(v.getRegistrationStatus(), v.getSubmittedAt(), v.getReviewedAt(), reviewer,
                        v.getReviewRemarks()),
                allocation == null ? null : refs.slot(allocation.getSlotId()), ParkingRefLoader.permitRef(permit),
                v.getCreatedAt(), v.getUpdatedAt());
    }

    /** The vehicle's slot: the live allocation covering now (active first). */
    private Map<UUID, ParkingAllocation> liveAllocations(Collection<Vehicle> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        Instant now = clock.instant();
        Map<UUID, ParkingAllocation> result = new HashMap<>();
        allocations.findLiveByVehicles(list.stream().map(Vehicle::getId).toList()).stream()
                .filter(a -> a.coversNow(now))
                .forEach(a -> result.merge(a.getVehicleId(), a,
                        (x, y) -> x.getStatus() == AllocationStatus.ACTIVE ? x : y));
        return result;
    }

    private Map<UUID, ParkingPermit> openPermits(Collection<Vehicle> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ParkingPermit> result = new HashMap<>();
        permits.findByVehicleIdInAndStatusIn(list.stream().map(Vehicle::getId).toList(),
                        EnumSet.of(PermitStatus.ACTIVE, PermitStatus.PENDING_PAYMENT, PermitStatus.SUSPENDED))
                .forEach(p -> result.merge(p.getVehicleId(), p, (x, y) -> x.getStatus() == PermitStatus.ACTIVE ? x : y));
        return result;
    }

    // ================================================================ helpers

    private Vehicle lock(UUID id) {
        Vehicle vehicle = vehicles.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.VEHICLE_NOT_FOUND));
        access.core().ensureInScope(vehicle.getSocietyId(), ErrorCode.VEHICLE_NOT_FOUND);
        return vehicle;
    }

    private Vehicle lockForAdmin(UUID id) {
        Vehicle vehicle = lock(id);
        access.core().require(vehicle.getSocietyId(), Permission.VEHICLE_MANAGE);
        return vehicle;
    }

    private void requireOwnFlat(Vehicle vehicle, Permission permission) {
        if (vehicle.getFlatId() == null || !access.core().isResidentOf(vehicle.getFlatId())) {
            throw new ApiException(ErrorCode.NOT_FLAT_RESIDENT, "You can only manage vehicles of your own flat.");
        }
        access.requireResidentFlat(vehicle.getFlatId(), permission);
    }

    private void requireFlat(UUID flatId, UUID societyId) {
        flats.findById(flatId)
                .filter(f -> f.getSocietyId().equals(societyId) && f.isActive())
                .orElseThrow(() -> ApiException.field(ErrorCode.INVALID_FLAT, "flatId", ErrorCode.INVALID_FLAT.defaultMessage()));
    }

    private void requireNumberFree(UUID societyId, String number) {
        if (vehicles.existsBySocietyIdAndRegistrationNumberAndActiveTrue(societyId, number)) {
            throw ApiException.field(ErrorCode.VEHICLE_ALREADY_REGISTERED, "registrationNumber",
                    ErrorCode.VEHICLE_ALREADY_REGISTERED.defaultMessage());
        }
    }

    private void requireNotInside(Vehicle vehicle) {
        if (sessions.findBySocietyIdAndVehicleNumberAndStatus(vehicle.getSocietyId(), vehicle.getRegistrationNumber(),
                SessionStatus.ACTIVE).isPresent()) {
            throw new ApiException(ErrorCode.SESSION_ALREADY_ACTIVE, "This vehicle is inside. Record its exit first.");
        }
    }

    private void deactivate(Vehicle vehicle) {
        requireNotInside(vehicle);
        vehicle.setActive(false);
        allocationService.endForVehicle(vehicle.getId(), "Vehicle deactivated");
    }

    private void markPending(Vehicle vehicle) {
        vehicle.setRegistrationStatus(RegistrationStatus.PENDING);
        vehicle.setSubmittedAt(clock.instant());
        vehicle.setReviewedAt(null);
        vehicle.setReviewedBy(null);
        vehicle.setReviewRemarks(null);
    }

    private void review(Vehicle vehicle, RegistrationStatus status, String remarks) {
        vehicle.setRegistrationStatus(status);
        vehicle.setReviewedAt(clock.instant());
        vehicle.setReviewedBy(access.currentUserId());
        vehicle.setReviewRemarks(Strings.trimToNull(remarks));
    }

    private static String requireReason(String remarks) {
        String value = Strings.trimToNull(remarks);
        if (value == null) {
            throw ApiException.field("remarks", "Give a reason.");
        }
        return value;
    }

    private static boolean hasIdentityChange(Vehicle vehicle, UpdateVehicleRequest request) {
        return (request.registrationNumber() != null
                && !VehicleNumbers.normalize(request.registrationNumber()).equals(vehicle.getRegistrationNumber()))
                || (request.vehicleType() != null && request.vehicleType() != vehicle.getVehicleType());
    }

    private void save(Vehicle vehicle) {
        try {
            vehicles.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.field(ErrorCode.VEHICLE_ALREADY_REGISTERED, "registrationNumber",
                    ErrorCode.VEHICLE_ALREADY_REGISTERED.defaultMessage());
        }
    }
}
