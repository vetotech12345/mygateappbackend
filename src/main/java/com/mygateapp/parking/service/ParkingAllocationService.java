package com.mygateapp.parking.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.flats.repository.FlatRepository;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.parking.dto.ParkingDtos.AllocationResponse;
import com.mygateapp.parking.dto.ParkingDtos.CreateAllocationRequest;
import com.mygateapp.parking.dto.ParkingDtos.IssuePermitRequest;
import com.mygateapp.parking.dto.ParkingDtos.PermitResponse;
import com.mygateapp.parking.dto.ParkingDtos.TransferAllocationRequest;
import com.mygateapp.parking.dto.ParkingDtos.UpdateAllocationRequest;
import com.mygateapp.parking.entity.ParkingAllocation;
import com.mygateapp.parking.entity.ParkingArea;
import com.mygateapp.parking.entity.ParkingPermit;
import com.mygateapp.parking.entity.ParkingSlot;
import com.mygateapp.parking.enums.ParkingEnums.AllocationStatus;
import com.mygateapp.parking.enums.ParkingEnums.AllocationType;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.enums.ParkingEnums.PermitType;
import com.mygateapp.parking.repository.ParkingAllocationRepository;
import com.mygateapp.parking.repository.ParkingPermitRepository;
import com.mygateapp.security.Permission;
import com.mygateapp.vehicle.VehicleNumbers;
import com.mygateapp.vehicle.entity.Vehicle;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import com.mygateapp.vehicle.repository.VehicleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slot allocations (permanent, temporary, visitor, paid, society) and parking permits. Every allocation locks its
 * slot and checks overlap first; the database exclusion constraint is the final guard.
 */
@Service
public class ParkingAllocationService {

    static final Set<PermitStatus> OPEN_PERMITS = EnumSet.of(PermitStatus.ACTIVE, PermitStatus.PENDING_PAYMENT,
            PermitStatus.SUSPENDED);
    private static final Duration RECENT = Duration.ofDays(30);

    private final ParkingAllocationRepository allocations;
    private final ParkingPermitRepository permits;
    private final VehicleRepository vehicles;
    private final FlatRepository flats;
    private final ParkingInventoryService inventory;
    private final ParkingAccess access;
    private final ParkingSupport support;
    private final ParkingRefLoader refLoader;
    private final AuditService audit;
    private final Clock clock;

    public ParkingAllocationService(ParkingAllocationRepository allocations, ParkingPermitRepository permits,
                                    VehicleRepository vehicles, FlatRepository flats, ParkingInventoryService inventory,
                                    ParkingAccess access, ParkingSupport support, ParkingRefLoader refLoader,
                                    AuditService audit, Clock clock) {
        this.allocations = allocations;
        this.permits = permits;
        this.vehicles = vehicles;
        this.flats = flats;
        this.inventory = inventory;
        this.access = access;
        this.support = support;
        this.refLoader = refLoader;
        this.audit = audit;
        this.clock = clock;
    }

    // =========================================================== allocations

    @Transactional
    public AllocationResponse create(CreateAllocationRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_ALLOCATION_MANAGE);
        Instant now = clock.instant();
        Instant from = request.validFrom() == null ? now : request.validFrom();
        Instant until = request.validUntil();
        if (until != null && !until.isAfter(from)) {
            throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "validUntil", "The end must be after the start.");
        }
        Vehicle vehicle = request.vehicleId() == null ? null : requireApprovedVehicle(request.vehicleId(), societyId);
        UUID flatId = request.flatId() != null ? request.flatId() : vehicle == null ? null : vehicle.getFlatId();
        validateHolder(request.allocationType(), flatId, vehicle, until, societyId);

        ParkingSlot slot = inventory.lockSlot(request.slotId(), societyId);
        inventory.requireSuitable(slot, usagesFor(request.allocationType(), vehicle),
                vehicle == null ? null : vehicle.getVehicleType());
        inventory.requireNoOverlap(slot, from, until, null);
        if (vehicle != null) {
            requireVehicleFree(vehicle.getId(), from, until, null);
        }
        ParkingAllocation allocation = allocate(societyId, slot, request.allocationType(), flatId,
                vehicle == null ? null : vehicle.getId(), null, from, until, AllocationStatus.ACTIVE,
                Strings.trimToNull(request.notes()));
        ParkingPermit permit = null;
        if (vehicle != null && !Boolean.FALSE.equals(request.issuePermit())) {
            permit = issue(societyId, permitTypeFor(request.allocationType(), vehicle), vehicle,
                    vehicle.getRegistrationNumber(), vehicle.getVehicleType(), flatId, slot.getId(), slot.getAreaId(),
                    allocation.getId(), null, holderName(vehicle, flatId), from, until, List.of(), PermitStatus.ACTIVE);
        }
        audit.record(societyId, "PARKING_ALLOCATION_CREATED", "PARKING_ALLOCATION", allocation.getId(),
                "Slot " + slot.getSlotNumber() + " allocated (" + request.allocationType() + ")",
                details("slot", slot.getSlotNumber(), "flatId", flatId, "vehicleId", vehicle == null ? null : vehicle.getId(),
                        "permit", permit == null ? null : permit.getPermitNumber()));
        support.notifyFlat(flatId, societyId, "Parking slot allocated",
                "Slot " + slot.getSlotNumber() + " has been allocated to your flat.",
                Notification.ReferenceType.PARKING_ALLOCATION, allocation.getId());
        return toResponse(allocation, refLoader.refs());
    }

    @Transactional
    public AllocationResponse update(UUID id, UpdateAllocationRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_ALLOCATION_MANAGE);
        ParkingAllocation allocation = lock(id, societyId);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.vehicleId() != null && !request.vehicleId().equals(allocation.getVehicleId())) {
            requireLive(allocation);
            Vehicle vehicle = requireApprovedVehicle(request.vehicleId(), societyId);
            if (allocation.getFlatId() != null && !allocation.getFlatId().equals(vehicle.getFlatId())) {
                throw ApiException.field("vehicleId", "This vehicle belongs to another flat.");
            }
            ParkingSlot slot = inventory.lockSlot(allocation.getSlotId(), societyId);
            inventory.requireSuitable(slot, null, vehicle.getVehicleType());
            requireVehicleFree(vehicle.getId(), allocation.getValidFrom(), allocation.getValidUntil(), allocation.getId());
            endPermits(allocation.getId(), PermitStatus.REVOKED, "Vehicle changed");
            allocation.setVehicleId(vehicle.getId());
            issue(societyId, permitTypeFor(allocation.getAllocationType(), vehicle), vehicle,
                    vehicle.getRegistrationNumber(), vehicle.getVehicleType(), allocation.getFlatId(), slot.getId(),
                    slot.getAreaId(), allocation.getId(), allocation.getRequestId(), holderName(vehicle, allocation.getFlatId()),
                    later(allocation.getValidFrom(), clock.instant()), allocation.getValidUntil(), List.of(), PermitStatus.ACTIVE);
            changes.put("vehicleId", vehicle.getId());
        }
        if (request.validUntil() != null) {
            requireLive(allocation);
            if (!request.validUntil().isAfter(allocation.getValidFrom())) {
                throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "validUntil", "The end must be after the start.");
            }
            ParkingSlot slot = inventory.lockSlot(allocation.getSlotId(), societyId);
            inventory.requireNoOverlap(slot, allocation.getValidFrom(), request.validUntil(), allocation.getId());
            allocation.setValidUntil(request.validUntil());
            permits.findByAllocationIdAndStatusIn(allocation.getId(), OPEN_PERMITS)
                    .forEach(p -> p.setValidUntil(request.validUntil()));
            changes.put("validUntil", request.validUntil());
        }
        if (request.status() != null && request.status() != allocation.getStatus()) {
            String reason = Strings.trimToNull(request.reason());
            switch (request.status()) {
                case REVOKED -> {
                    requireLive(allocation);
                    end(allocation, AllocationStatus.REVOKED, reason == null ? "Cancelled by the society office" : reason);
                }
                case SUSPENDED -> {
                    if (allocation.getStatus() != AllocationStatus.ACTIVE) {
                        throw new ApiException(ErrorCode.ALLOCATION_NOT_ACTIVE);
                    }
                    allocation.setStatus(AllocationStatus.SUSPENDED);
                    setPermitStatus(allocation.getId(), PermitStatus.ACTIVE, PermitStatus.SUSPENDED);
                }
                case ACTIVE -> {
                    if (allocation.getStatus() != AllocationStatus.SUSPENDED) {
                        throw new ApiException(ErrorCode.ALLOCATION_NOT_ACTIVE, "Only suspended allocations can be resumed.");
                    }
                    allocation.setStatus(AllocationStatus.ACTIVE);
                    setPermitStatus(allocation.getId(), PermitStatus.SUSPENDED, PermitStatus.ACTIVE);
                }
                default -> throw ApiException.field("status", "Use REVOKED, SUSPENDED or ACTIVE.");
            }
            changes.put("status", request.status().name());
            if (reason != null) {
                changes.put("reason", reason);
            }
        }
        if (request.notes() != null) {
            allocation.setNotes(Strings.trimToNull(request.notes()));
        }
        allocations.flush();
        audit.record(societyId, "PARKING_ALLOCATION_UPDATED", "PARKING_ALLOCATION", id, "Parking allocation updated", changes);
        if (request.status() == AllocationStatus.REVOKED) {
            support.notifyFlat(allocation.getFlatId(), societyId, "Parking allocation ended",
                    "A parking slot allocation of your flat has been cancelled by the society office.",
                    Notification.ReferenceType.PARKING_ALLOCATION, id);
        }
        return toResponse(allocation, refLoader.refs());
    }

    /** Moves an allocation to another slot (and optionally another flat/vehicle) in one transaction. */
    @Transactional
    public AllocationResponse transfer(UUID id, TransferAllocationRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_ALLOCATION_MANAGE);
        ParkingAllocation old = lock(id, societyId);
        requireLive(old);
        Instant now = clock.instant();
        Instant from = later(old.getValidFrom(), now);
        Instant until = old.getValidUntil();
        Vehicle vehicle = request.vehicleId() != null ? requireApprovedVehicle(request.vehicleId(), societyId)
                : old.getVehicleId() == null ? null : vehicles.findById(old.getVehicleId()).orElse(null);
        UUID flatId = request.flatId() != null ? request.flatId() : request.vehicleId() != null && vehicle != null
                ? vehicle.getFlatId() : old.getFlatId();
        validateHolder(old.getAllocationType(), flatId, vehicle, until, societyId);
        String reason = Strings.trimToNull(request.reason());

        ParkingSlot oldSlot = inventory.lockSlot(old.getSlotId(), societyId);
        end(old, AllocationStatus.REVOKED, "Transferred" + (reason == null ? "" : ": " + reason));
        allocations.flush();

        ParkingSlot slot = inventory.lockSlot(request.slotId(), societyId);
        inventory.requireSuitable(slot, usagesFor(old.getAllocationType(), vehicle),
                vehicle == null ? null : vehicle.getVehicleType());
        inventory.requireNoOverlap(slot, from, until, null);
        if (vehicle != null) {
            requireVehicleFree(vehicle.getId(), from, until, null);
        }
        ParkingAllocation moved = allocate(societyId, slot, old.getAllocationType(), flatId,
                vehicle == null ? null : vehicle.getId(), old.getRequestId(), from, until,
                AllocationStatus.ACTIVE, old.getNotes());
        if (vehicle != null) {
            issue(societyId, permitTypeFor(old.getAllocationType(), vehicle), vehicle, vehicle.getRegistrationNumber(),
                    vehicle.getVehicleType(), flatId, slot.getId(), slot.getAreaId(), moved.getId(), old.getRequestId(),
                    holderName(vehicle, flatId), from, until, List.of(), PermitStatus.ACTIVE);
        }
        audit.record(societyId, "PARKING_ALLOCATION_TRANSFERRED", "PARKING_ALLOCATION", moved.getId(),
                "Allocation moved from " + oldSlot.getSlotNumber() + " to " + slot.getSlotNumber(),
                details("previousAllocationId", old.getId(), "reason", reason));
        support.notifyFlat(flatId, societyId, "Parking slot changed",
                "Your parking slot is now " + slot.getSlotNumber() + ".",
                Notification.ReferenceType.PARKING_ALLOCATION, moved.getId());
        return toResponse(moved, refLoader.refs());
    }

    @Transactional(readOnly = true)
    public PageResponse<AllocationResponse> list(AllocationStatus status, AllocationType type, UUID flatId, UUID slotId,
                                                 UUID vehicleId, Integer page, Integer size) {
        UUID societyId = access.societyId();
        if (!access.core().hasPermission(societyId, Permission.PARKING_ALLOCATION_MANAGE)) {
            access.requireAdmin(Permission.PARKING_LOG_VIEW);
        }
        var result = allocations.search(societyId, status, type, flatId, slotId, vehicleId,
                Pages.of(page, size, Sort.by(Sort.Direction.DESC, "validFrom")));
        ParkingRefLoader.Refs refs = preload(result.getContent());
        Map<UUID, ParkingPermit> permitByAllocation = permitsByAllocation(result.getContent());
        return PageResponse.of(result, a -> toResponse(a, refs, permitByAllocation.get(a.getId())));
    }

    @Transactional(readOnly = true)
    public List<AllocationResponse> mine() {
        ParkingAccess.Scope scope = access.residentScope();
        if (scope.flatIds().isEmpty()) {
            return List.of();
        }
        Instant cutoff = clock.instant().minus(RECENT);
        List<ParkingAllocation> list = allocations.findByFlats(scope.flatIds()).stream()
                .filter(a -> a.getStatus().live() || (a.getEndedAt() != null && a.getEndedAt().isAfter(cutoff))
                        || (a.getValidUntil() != null && a.getValidUntil().isAfter(cutoff)))
                .toList();
        ParkingRefLoader.Refs refs = preload(list);
        Map<UUID, ParkingPermit> permitByAllocation = permitsByAllocation(list);
        return list.stream().map(a -> toResponse(a, refs, permitByAllocation.get(a.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public AllocationResponse get(UUID id) {
        ParkingAccess.Scope scope = access.readScope(Permission.PARKING_LOG_VIEW, false);
        ParkingAllocation allocation = allocations.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.ALLOCATION_NOT_FOUND));
        access.ensureSameSociety(allocation.getSocietyId(), scope.societyId(), ErrorCode.ALLOCATION_NOT_FOUND);
        if (!scope.canSeeFlat(allocation.getFlatId())) {
            throw new ApiException(ErrorCode.ALLOCATION_NOT_FOUND);
        }
        return toResponse(allocation, refLoader.refs());
    }

    // ================================================================ permits

    @Transactional
    public PermitResponse issuePermit(IssuePermitRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_ALLOCATION_MANAGE);
        if (request.type() == PermitType.PAID_MONTHLY) {
            throw ApiException.field("type", "Paid monthly permits are issued through a PAID_PERMIT request and payment.");
        }
        Instant now = clock.instant();
        Instant from = request.validFrom() == null ? now : request.validFrom();
        Instant until = request.validUntil();
        if (until != null && !until.isAfter(from)) {
            throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "validUntil", "The end must be after the start.");
        }
        if ((request.type() == PermitType.TEMPORARY || request.type() == PermitType.VISITOR) && until == null) {
            throw ApiException.field("validUntil", "Temporary and visitor permits need an end time.");
        }
        Vehicle vehicle = request.vehicleId() == null ? null : requireApprovedVehicle(request.vehicleId(), societyId);
        String number = vehicle != null ? vehicle.getRegistrationNumber()
                : VehicleNumbers.requireLookup(request.vehicleNumber(), "vehicleNumber");
        VehicleType type = vehicle != null ? vehicle.getVehicleType() : request.vehicleType();
        if (type == null) {
            throw ApiException.field("vehicleType", "vehicleType is required for an unregistered vehicle.");
        }
        UUID flatId = request.flatId() != null ? request.flatId() : vehicle == null ? null : vehicle.getFlatId();
        if (request.type() == PermitType.RESIDENT && flatId == null) {
            throw ApiException.field("flatId", "Resident permits belong to a flat.");
        }
        if (flatId != null) {
            requireFlat(flatId, societyId);
        }
        UUID allocationId = null;
        UUID slotId = request.slotId();
        UUID areaId = request.areaId();
        if (slotId != null) {
            ParkingSlot slot = inventory.lockSlot(slotId, societyId);
            AllocationType allocationType = allocationTypeFor(request.type());
            inventory.requireSuitable(slot, usagesFor(allocationType, vehicle), type);
            inventory.requireNoOverlap(slot, from, until, null);
            allocationId = allocate(societyId, slot, allocationType, flatId, vehicle == null ? null : vehicle.getId(), null,
                    from, until, AllocationStatus.ACTIVE, "Issued with permit").getId();
            areaId = slot.getAreaId();
        } else if (areaId != null) {
            inventory.requireArea(areaId, societyId);
        }
        String holder = Strings.trimToNull(request.holderName()) != null ? request.holderName().trim()
                : holderName(vehicle, flatId);
        ParkingPermit permit = issue(societyId, request.type(), vehicle, number, type, flatId, slotId, areaId, allocationId,
                null, holder, from, until, request.conditions() == null ? List.of() : request.conditions(),
                PermitStatus.ACTIVE);
        audit.record(societyId, "PARKING_PERMIT_ISSUED", "PARKING_PERMIT", permit.getId(),
                "Permit " + permit.getPermitNumber() + " issued for " + number,
                details("type", request.type().name(), "validUntil", until));
        support.notifyFlat(flatId, societyId, "Parking permit issued",
                "Permit " + permit.getPermitNumber() + " is active for " + number + ".",
                Notification.ReferenceType.PARKING_PERMIT, permit.getId());
        return toPermitResponse(permit, refLoader.refs());
    }

    @Transactional
    public PermitResponse revokePermit(UUID id, String reason) {
        UUID societyId = access.requireAdmin(Permission.PARKING_ALLOCATION_MANAGE);
        ParkingPermit permit = permits.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.PERMIT_NOT_FOUND));
        access.ensureSameSociety(permit.getSocietyId(), societyId, ErrorCode.PERMIT_NOT_FOUND);
        if (!OPEN_PERMITS.contains(permit.getStatus())) {
            throw new ApiException(ErrorCode.PERMIT_NOT_ACTIVE);
        }
        endPermit(permit, PermitStatus.REVOKED, reason);
        if (permit.getAllocationId() != null) {
            allocations.findByIdForUpdate(permit.getAllocationId())
                    .filter(a -> a.getStatus().live())
                    .ifPresent(a -> end(a, AllocationStatus.REVOKED, "Permit revoked: " + reason));
        }
        audit.record(societyId, "PARKING_PERMIT_REVOKED", "PARKING_PERMIT", id,
                "Permit " + permit.getPermitNumber() + " revoked", Map.of("reason", reason));
        support.notifyFlat(permit.getFlatId(), societyId, "Parking permit revoked",
                "Permit " + permit.getPermitNumber() + " is no longer valid.", Notification.ReferenceType.PARKING_PERMIT, id);
        return toPermitResponse(permit, refLoader.refs());
    }

    @Transactional(readOnly = true)
    public List<PermitResponse> myPermits() {
        ParkingAccess.Scope scope = access.residentScope();
        if (scope.flatIds().isEmpty()) {
            return List.of();
        }
        Instant cutoff = clock.instant().minus(RECENT);
        List<ParkingPermit> list = permits.findByFlats(scope.flatIds()).stream()
                .filter(p -> OPEN_PERMITS.contains(p.getStatus()) || p.getUpdatedAt().isAfter(cutoff))
                .toList();
        ParkingRefLoader.Refs refs = refLoader.refs()
                .preloadFlats(list.stream().map(ParkingPermit::getFlatId).toList())
                .preloadSlots(list.stream().map(ParkingPermit::getSlotId).toList());
        return list.stream().map(p -> toPermitResponse(p, refs)).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<PermitResponse> listPermits(PermitStatus status, PermitType type, String q, Integer page,
                                                    Integer size) {
        UUID societyId = access.societyId();
        if (!access.core().hasPermission(societyId, Permission.PARKING_ALLOCATION_MANAGE)) {
            access.requireAdmin(Permission.PARKING_LOG_VIEW);
        }
        String like = q == null || q.isBlank() ? null : "%" + VehicleNumbers.normalize(q).replace("%", "") + "%";
        var result = permits.search(societyId, status, type, like,
                Pages.of(page, size, Sort.by(Sort.Direction.DESC, "validFrom")));
        ParkingRefLoader.Refs refs = refLoader.refs()
                .preloadFlats(result.getContent().stream().map(ParkingPermit::getFlatId).toList())
                .preloadSlots(result.getContent().stream().map(ParkingPermit::getSlotId).toList());
        return PageResponse.of(result, p -> toPermitResponse(p, refs));
    }

    @Transactional(readOnly = true)
    public PermitResponse getPermit(UUID id) {
        ParkingAccess.Scope scope = access.readScope(Permission.PARKING_LOG_VIEW, true);
        ParkingPermit permit = permits.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PERMIT_NOT_FOUND));
        access.ensureSameSociety(permit.getSocietyId(), scope.societyId(), ErrorCode.PERMIT_NOT_FOUND);
        if (!scope.canSeeFlat(permit.getFlatId())) {
            throw new ApiException(ErrorCode.PERMIT_NOT_FOUND);
        }
        return toPermitResponse(permit, refLoader.refs());
    }

    // ================================================ internal (other services)

    /** Creates an allocation on a slot the caller has locked and checked. */
    public ParkingAllocation allocate(UUID societyId, ParkingSlot lockedSlot, AllocationType type, UUID flatId,
                                      UUID vehicleId, UUID requestId, Instant from, Instant until,
                                      AllocationStatus status, String notes) {
        ParkingAllocation allocation = new ParkingAllocation();
        allocation.setSocietyId(societyId);
        allocation.setSlotId(lockedSlot.getId());
        allocation.setFlatId(flatId);
        allocation.setVehicleId(vehicleId);
        allocation.setRequestId(requestId);
        allocation.setAllocationType(type);
        allocation.setStatus(status);
        allocation.setValidFrom(from);
        allocation.setValidUntil(until);
        allocation.setNotes(notes);
        UUID actor = currentUserOrNull();
        allocation.setCreatedBy(actor);
        allocation.setApprovedBy(actor);
        allocation.setApprovedAt(clock.instant());
        try {
            return allocations.saveAndFlush(allocation);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.ALLOCATION_CONFLICT);
        }
    }

    public ParkingPermit issue(UUID societyId, PermitType type, Vehicle vehicle, String number, VehicleType vehicleType,
                               UUID flatId, UUID slotId, UUID areaId, UUID allocationId, UUID requestId,
                               String holderName, Instant from, Instant until, List<String> conditions,
                               PermitStatus status) {
        ParkingPermit permit = new ParkingPermit();
        permit.setSocietyId(societyId);
        permit.setPermitNumber(support.permitNumber(societyId, typeCode(type)));
        permit.setPermitType(type);
        permit.setStatus(status);
        permit.setVehicleId(vehicle == null ? null : vehicle.getId());
        permit.setVehicleNumber(number);
        permit.setVehicleType(vehicleType);
        permit.setHolderName(holderName);
        permit.setFlatId(flatId);
        permit.setSlotId(slotId);
        permit.setAreaId(areaId);
        permit.setAllocationId(allocationId);
        permit.setRequestId(requestId);
        permit.setValidFrom(from);
        permit.setValidUntil(until);
        List<String> allConditions = new ArrayList<>(conditions);
        if (slotId != null) {
            ParkingSlot slot = refLoader.refs().slotEntity(slotId);
            allConditions.addFirst("Valid only for slot " + slot.getSlotNumber());
        } else if (areaId != null) {
            ParkingArea area = refLoader.refs().area(areaId);
            allConditions.addFirst("Valid only in " + area.getName());
        }
        permit.setConditions(allConditions);
        UUID actor = currentUserOrNull();
        permit.setIssuedBy(actor);
        permit.setIssuedByName(actor == null ? "Society office" : support.displayName(actor));
        return permits.saveAndFlush(permit);
    }

    /** Ends an allocation now (or cancels a future one) and closes its permits. */
    public void end(ParkingAllocation allocation, AllocationStatus status, String reason) {
        Instant now = clock.instant();
        allocation.setStatus(status);
        allocation.setEndedAt(now);
        allocation.setEndedBy(currentUserOrNull());
        allocation.setEndReason(reason == null ? null : truncate(reason, 200));
        if (now.isAfter(allocation.getValidFrom()) && (allocation.getValidUntil() == null || allocation.getValidUntil().isAfter(now))) {
            allocation.setValidUntil(now);
        }
        endPermits(allocation.getId(), status == AllocationStatus.EXPIRED ? PermitStatus.EXPIRED : PermitStatus.REVOKED, reason);
    }

    public void endPermit(ParkingPermit permit, PermitStatus status, String reason) {
        Instant now = clock.instant();
        permit.setStatus(status);
        if (status == PermitStatus.REVOKED) {
            permit.setRevokedAt(now);
            permit.setRevokedBy(currentUserOrNull());
            permit.setRevokeReason(reason == null ? null : truncate(reason, 200));
        }
        if (now.isAfter(permit.getValidFrom()) && (permit.getValidUntil() == null || permit.getValidUntil().isAfter(now))) {
            permit.setValidUntil(now);
        }
    }

    /** Vehicle identity changed or registration suspended: its live allocations and permits are suspended. */
    public void suspendForVehicle(UUID vehicleId) {
        allocations.findLiveByVehicle(vehicleId).stream()
                .filter(a -> a.getStatus() == AllocationStatus.ACTIVE)
                .forEach(a -> a.setStatus(AllocationStatus.SUSPENDED));
        permits.findByVehicleIdAndStatusIn(vehicleId, EnumSet.of(PermitStatus.ACTIVE))
                .forEach(p -> p.setStatus(PermitStatus.SUSPENDED));
    }

    /** Vehicle re-approved: suspended allocations and permits become active again. */
    public void reinstateForVehicle(UUID vehicleId) {
        allocations.findLiveByVehicle(vehicleId).stream()
                .filter(a -> a.getStatus() == AllocationStatus.SUSPENDED)
                .forEach(a -> a.setStatus(AllocationStatus.ACTIVE));
        permits.findByVehicleIdAndStatusIn(vehicleId, EnumSet.of(PermitStatus.SUSPENDED))
                .forEach(p -> p.setStatus(PermitStatus.ACTIVE));
    }

    /** Vehicle deactivated: its allocations and permits end. */
    public void endForVehicle(UUID vehicleId, String reason) {
        allocations.findLiveByVehicle(vehicleId).forEach(a -> end(a, AllocationStatus.REVOKED, reason));
        permits.findByVehicleIdAndStatusIn(vehicleId, OPEN_PERMITS).forEach(p -> endPermit(p, PermitStatus.REVOKED, reason));
    }

    public void renumberPermits(UUID vehicleId, String number, VehicleType type) {
        permits.findByVehicleIdAndStatusIn(vehicleId, OPEN_PERMITS).forEach(p -> {
            p.setVehicleNumber(number);
            p.setVehicleType(type);
        });
    }

    public static Set<ParkingUsage> usagesFor(AllocationType type, Vehicle vehicle) {
        return switch (type) {
            case PERMANENT, TEMPORARY -> ParkingUsage.servingCategory(Category.RESIDENT);
            case VISITOR -> ParkingUsage.servingCategory(Category.VISITOR);
            case PAID -> EnumSet.of(ParkingUsage.PAID, ParkingUsage.COMMON, ParkingUsage.RESIDENT);
            case SOCIETY -> vehicle != null && vehicle.getCategory() != Category.RESIDENT
                    ? ParkingUsage.servingCategory(vehicle.getCategory())
                    : EnumSet.of(ParkingUsage.SOCIETY, ParkingUsage.STAFF, ParkingUsage.SERVICE, ParkingUsage.EMERGENCY,
                    ParkingUsage.RESERVED);
        };
    }

    public static PermitType permitTypeFor(AllocationType type, Vehicle vehicle) {
        return switch (type) {
            case PERMANENT -> PermitType.RESIDENT;
            case TEMPORARY -> PermitType.TEMPORARY;
            case VISITOR -> PermitType.VISITOR;
            case PAID -> PermitType.PAID_MONTHLY;
            case SOCIETY -> switch (vehicle == null ? Category.SOCIETY : vehicle.getCategory()) {
                case STAFF -> PermitType.STAFF;
                case SERVICE -> PermitType.SERVICE;
                case EMERGENCY -> PermitType.EMERGENCY;
                default -> PermitType.SOCIETY;
            };
        };
    }

    static AllocationType allocationTypeFor(PermitType type) {
        return switch (type) {
            case RESIDENT -> AllocationType.PERMANENT;
            case TEMPORARY -> AllocationType.TEMPORARY;
            case VISITOR -> AllocationType.VISITOR;
            case PAID_MONTHLY -> AllocationType.PAID;
            case SOCIETY, STAFF, SERVICE, EMERGENCY -> AllocationType.SOCIETY;
        };
    }

    // ================================================================ mapping

    public AllocationResponse toResponse(ParkingAllocation a, ParkingRefLoader.Refs refs) {
        ParkingPermit permit = permits.findByAllocationIdIn(List.of(a.getId())).stream()
                .max(Comparator.comparing(ParkingPermit::getValidFrom)).orElse(null);
        return toResponse(a, refs, permit);
    }

    private AllocationResponse toResponse(ParkingAllocation a, ParkingRefLoader.Refs refs, ParkingPermit permit) {
        return new AllocationResponse(a.getId(), refs.slot(a.getSlotId()), a.getAllocationType(), a.getStatus(),
                refs.flat(a.getFlatId()), refs.vehicle(a.getVehicleId()), a.getValidFrom(), a.getValidUntil(),
                ParkingRefLoader.permitRef(permit), a.getRequestId(), a.getNotes(), a.getEndReason(), a.getCreatedAt());
    }

    public PermitResponse toPermitResponse(ParkingPermit p, ParkingRefLoader.Refs refs) {
        return new PermitResponse(p.getId(), p.getPermitNumber(), p.getPermitType(), p.getStatus(), p.getHolderName(),
                p.getVehicleNumber(), p.getVehicleType(), p.getVehicleId(), refs.flat(p.getFlatId()),
                refs.slot(p.getSlotId()), refs.areaRefOf(p.getAreaId()), p.getValidFrom(), p.getValidUntil(),
                p.getIssuedByName(), p.getConditions(), p.getRevokedAt(), p.getRevokeReason());
    }

    private ParkingRefLoader.Refs preload(Collection<ParkingAllocation> list) {
        return refLoader.refs()
                .preloadSlots(list.stream().map(ParkingAllocation::getSlotId).toList())
                .preloadFlats(list.stream().map(ParkingAllocation::getFlatId).toList())
                .preloadVehicles(list.stream().map(ParkingAllocation::getVehicleId).toList());
    }

    private Map<UUID, ParkingPermit> permitsByAllocation(Collection<ParkingAllocation> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ParkingPermit> result = new LinkedHashMap<>();
        permits.findByAllocationIdIn(list.stream().map(ParkingAllocation::getId).toList()).stream()
                .sorted(Comparator.comparing(ParkingPermit::getValidFrom))
                .forEach(p -> result.put(p.getAllocationId(), p));
        return result;
    }

    // ================================================================ helpers

    private ParkingAllocation lock(UUID id, UUID societyId) {
        ParkingAllocation allocation = allocations.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.ALLOCATION_NOT_FOUND));
        access.ensureSameSociety(allocation.getSocietyId(), societyId, ErrorCode.ALLOCATION_NOT_FOUND);
        return allocation;
    }

    private static void requireLive(ParkingAllocation allocation) {
        if (!allocation.getStatus().live()) {
            throw new ApiException(ErrorCode.ALLOCATION_NOT_ACTIVE);
        }
    }

    public Vehicle requireApprovedVehicle(UUID vehicleId, UUID societyId) {
        Vehicle vehicle = vehicles.findById(vehicleId).orElseThrow(() -> new ApiException(ErrorCode.VEHICLE_NOT_FOUND));
        access.ensureSameSociety(vehicle.getSocietyId(), societyId, ErrorCode.VEHICLE_NOT_FOUND);
        if (!vehicle.isActive()) {
            throw new ApiException(ErrorCode.VEHICLE_INACTIVE);
        }
        if (!vehicle.isApproved()) {
            throw new ApiException(ErrorCode.VEHICLE_NOT_APPROVED);
        }
        return vehicle;
    }

    private void requireVehicleFree(UUID vehicleId, Instant from, Instant until, UUID excludeId) {
        Instant end = until == null ? com.mygateapp.common.TimeRanges.MAX : until;
        boolean busy = allocations.findLiveByVehicle(vehicleId).stream()
                .filter(a -> !a.getId().equals(excludeId))
                .anyMatch(a -> a.getValidFrom().isBefore(end) && (a.getValidUntil() == null || a.getValidUntil().isAfter(from)));
        if (busy) {
            throw new ApiException(ErrorCode.ALLOCATION_CONFLICT, "This vehicle already has a slot for this period.");
        }
    }

    private void validateHolder(AllocationType type, UUID flatId, Vehicle vehicle, Instant until, UUID societyId) {
        if ((type == AllocationType.PERMANENT || type == AllocationType.TEMPORARY) && flatId == null) {
            throw ApiException.field("flatId", "Resident allocations belong to a flat.");
        }
        if (type == AllocationType.TEMPORARY && until == null) {
            throw ApiException.field("validUntil", "Temporary allocations need an end date.");
        }
        if (flatId != null) {
            requireFlat(flatId, societyId);
        }
        if (vehicle != null && flatId != null && vehicle.getFlatId() != null && !vehicle.getFlatId().equals(flatId)) {
            throw ApiException.field("vehicleId", "This vehicle belongs to another flat.");
        }
    }

    private Flat requireFlat(UUID flatId, UUID societyId) {
        return flats.findById(flatId)
                .filter(f -> f.getSocietyId().equals(societyId) && f.isActive())
                .orElseThrow(() -> ApiException.field(ErrorCode.INVALID_FLAT, "flatId", ErrorCode.INVALID_FLAT.defaultMessage()));
    }

    private void endPermits(UUID allocationId, PermitStatus status, String reason) {
        permits.findByAllocationIdAndStatusIn(allocationId, OPEN_PERMITS).forEach(p -> endPermit(p, status, reason));
    }

    private void setPermitStatus(UUID allocationId, PermitStatus from, PermitStatus to) {
        permits.findByAllocationIdAndStatusIn(allocationId, EnumSet.of(from)).forEach(p -> p.setStatus(to));
    }

    private String holderName(Vehicle vehicle, UUID flatId) {
        if (vehicle != null && vehicle.getOwnerName() != null) {
            return vehicle.getOwnerName();
        }
        return flatId == null ? "Society" : refLoader.refs().flat(flatId).label();
    }

    private UUID currentUserOrNull() {
        try {
            return access.currentUserId();
        } catch (ApiException e) {
            return null;
        }
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    static String typeCode(PermitType type) {
        return switch (type) {
            case RESIDENT -> "RES";
            case TEMPORARY -> "TMP";
            case VISITOR -> "VIS";
            case PAID_MONTHLY -> "PAY";
            case SOCIETY -> "SOC";
            case STAFF -> "STF";
            case SERVICE -> "SRV";
            case EMERGENCY -> "EMG";
        };
    }

    static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Map.of without null values (audit details). */
    static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }
}
