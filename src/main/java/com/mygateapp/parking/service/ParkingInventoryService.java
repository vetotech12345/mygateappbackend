package com.mygateapp.parking.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.TimeRanges;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.parking.dto.ParkingDtos.AllocationSummary;
import com.mygateapp.parking.dto.ParkingDtos.AreaAvailability;
import com.mygateapp.parking.dto.ParkingDtos.AreaResponse;
import com.mygateapp.parking.dto.ParkingDtos.AvailabilityResponse;
import com.mygateapp.parking.dto.ParkingDtos.BulkCreateSlotsRequest;
import com.mygateapp.parking.dto.ParkingDtos.CreateAreaRequest;
import com.mygateapp.parking.dto.ParkingDtos.CreateSlotRequest;
import com.mygateapp.parking.dto.ParkingDtos.SlotResponse;
import com.mygateapp.parking.dto.ParkingDtos.UpdateAreaRequest;
import com.mygateapp.parking.dto.ParkingDtos.UpdateSlotRequest;
import com.mygateapp.parking.entity.ParkingAllocation;
import com.mygateapp.parking.entity.ParkingArea;
import com.mygateapp.parking.entity.ParkingSession;
import com.mygateapp.parking.entity.ParkingSlot;
import com.mygateapp.parking.entity.SocietyParkingSettings;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import com.mygateapp.parking.enums.ParkingEnums.SlotOperationalStatus;
import com.mygateapp.parking.enums.ParkingEnums.SlotStatus;
import com.mygateapp.parking.repository.ParkingAllocationRepository;
import com.mygateapp.parking.repository.ParkingAreaRepository;
import com.mygateapp.parking.repository.ParkingSessionRepository;
import com.mygateapp.parking.repository.ParkingSlotRepository;
import com.mygateapp.security.Permission;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parking areas, slots, the live state of each slot and availability. Allocation (who may use a slot when),
 * occupancy (an active session), reservation and operational status are four independent facts.
 */
@Service
public class ParkingInventoryService {

    static final Duration MAX_AVAILABILITY_WINDOW = Duration.ofDays(31);
    static final int MAX_SLOT_PAGE = 500;

    private final ParkingAreaRepository areas;
    private final ParkingSlotRepository slots;
    private final ParkingAllocationRepository allocations;
    private final ParkingSessionRepository sessions;
    private final ParkingSettingsStore settingsStore;
    private final ParkingAccess access;
    private final ParkingRefLoader refLoader;
    private final AuditService audit;
    private final Clock clock;

    public ParkingInventoryService(ParkingAreaRepository areas, ParkingSlotRepository slots,
                                   ParkingAllocationRepository allocations, ParkingSessionRepository sessions,
                                   ParkingSettingsStore settingsStore, ParkingAccess access, ParkingRefLoader refLoader,
                                   AuditService audit, Clock clock) {
        this.areas = areas;
        this.slots = slots;
        this.allocations = allocations;
        this.sessions = sessions;
        this.settingsStore = settingsStore;
        this.access = access;
        this.refLoader = refLoader;
        this.audit = audit;
        this.clock = clock;
    }

    // ================================================================== areas

    @Transactional
    public AreaResponse createArea(CreateAreaRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_INVENTORY_MANAGE);
        String code = request.code().trim().toUpperCase();
        String name = request.name().trim();
        if (areas.codeOrNameTaken(societyId, code, name, null)) {
            throw new ApiException(ErrorCode.PARKING_AREA_TAKEN);
        }
        ParkingArea area = new ParkingArea();
        area.setSocietyId(societyId);
        area.setCode(code);
        area.setName(name);
        area.setLevel(Strings.trimToNull(request.level()));
        area.setDescription(Strings.trimToNull(request.description()));
        area.setUsage(request.usage());
        area.setVehicleTypes(sortedNames(request.vehicleTypes()));
        saveArea(area);
        audit.record(societyId, "PARKING_AREA_CREATED", "PARKING_AREA", area.getId(),
                "Parking area " + code + " (" + name + ") created", Map.of("usage", area.getUsage().name()));
        return areaResponse(area, Map.of());
    }

    @Transactional
    public AreaResponse updateArea(UUID id, UpdateAreaRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_INVENTORY_MANAGE);
        ParkingArea area = requireArea(id, societyId);
        if (request.name() != null) {
            String name = request.name().trim();
            if (areas.existsBySocietyIdAndNameIgnoreCaseAndIdNot(societyId, name, id)) {
                throw new ApiException(ErrorCode.PARKING_AREA_TAKEN);
            }
            area.setName(name);
        }
        if (request.level() != null) {
            area.setLevel(Strings.trimToNull(request.level()));
        }
        if (request.description() != null) {
            area.setDescription(Strings.trimToNull(request.description()));
        }
        if (request.usage() != null) {
            area.setUsage(request.usage());
        }
        if (request.vehicleTypes() != null) {
            if (request.vehicleTypes().isEmpty()) {
                throw ApiException.field("vehicleTypes", "Choose at least one vehicle type");
            }
            area.setVehicleTypes(sortedNames(request.vehicleTypes()));
        }
        if (request.status() != null) {
            area.setStatus(request.status());
        }
        saveArea(area);
        audit.record(societyId, "PARKING_AREA_UPDATED", "PARKING_AREA", id, "Parking area " + area.getCode() + " updated",
                Map.of("status", area.getStatus().name(), "usage", area.getUsage().name()));
        return areaResponse(area, statesOf(societyId, slots.findByAreaId(id)));
    }

    @Transactional(readOnly = true)
    public List<AreaResponse> listAreas(boolean includeInactive) {
        UUID societyId = access.requireViewer();
        return areaResponses(societyId, includeInactive);
    }

    @Transactional(readOnly = true)
    public AreaResponse getArea(UUID id) {
        UUID societyId = access.requireViewer();
        ParkingArea area = requireArea(id, societyId);
        return areaResponse(area, statesOf(societyId, slots.findByAreaId(id)));
    }

    /** Areas with live slot counts (config screen, guard dashboard). */
    @Transactional(readOnly = true)
    public List<AreaResponse> areaResponses(UUID societyId, boolean includeInactive) {
        Map<UUID, SlotState> states = statesOf(societyId, slots.findBySocietyId(societyId));
        return areas.findBySocietyIdOrderByName(societyId).stream()
                .filter(a -> includeInactive || a.isActive())
                .map(a -> areaResponse(a, states))
                .toList();
    }

    // ================================================================== slots

    @Transactional
    public SlotResponse createSlot(CreateSlotRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_INVENTORY_MANAGE);
        ParkingArea area = requireArea(request.areaId(), societyId);
        ParkingSlot slot = newSlot(area, request.number().trim().toUpperCase(), request.vehicleType(), request.usage());
        slot.setNotes(Strings.trimToNull(request.notes()));
        saveSlot(slot);
        audit.record(societyId, "PARKING_SLOT_CREATED", "PARKING_SLOT", slot.getId(),
                "Slot " + slot.getSlotNumber() + " created in " + area.getCode(),
                Map.of("usage", slot.getUsage().name(), "vehicleType", slot.getVehicleType().name()));
        return slotResponse(slot, statesOf(societyId, List.of(slot)), refLoader.refs());
    }

    @Transactional
    public List<SlotResponse> bulkCreateSlots(BulkCreateSlotsRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_INVENTORY_MANAGE);
        if (request.to() < request.from()) {
            throw ApiException.field("to", "'to' must not be less than 'from'");
        }
        if (request.to() - request.from() >= 500) {
            throw ApiException.field("to", "At most 500 slots can be created at once");
        }
        ParkingArea area = requireArea(request.areaId(), societyId);
        int padding = request.padding() == null ? 0 : request.padding();
        List<ParkingSlot> created = new ArrayList<>();
        for (int n = request.from(); n <= request.to(); n++) {
            String number = (request.prefix().trim() + (padding == 0 ? String.valueOf(n)
                    : String.format("%0" + padding + "d", n))).toUpperCase();
            if (number.length() > 20) {
                throw ApiException.field("prefix", "Slot numbers must be at most 20 characters");
            }
            created.add(newSlot(area, number, request.vehicleType(), request.usage()));
        }
        try {
            slots.saveAllAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.PARKING_SLOT_TAKEN);
        }
        audit.record(societyId, "PARKING_SLOTS_CREATED", "PARKING_AREA", area.getId(),
                created.size() + " slots created in " + area.getCode(), Map.of("count", created.size()));
        Map<UUID, SlotState> states = statesOf(societyId, created);
        ParkingRefLoader.Refs refs = refLoader.refs();
        return created.stream().map(s -> slotResponse(s, states, refs)).toList();
    }

    @Transactional
    public SlotResponse updateSlot(UUID id, UpdateSlotRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_INVENTORY_MANAGE);
        ParkingSlot slot = requireSlot(id, societyId);
        ParkingArea area = requireArea(slot.getAreaId(), societyId);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.number() != null && !request.number().equalsIgnoreCase(slot.getSlotNumber())) {
            String number = request.number().trim().toUpperCase();
            if (slots.existsByAreaIdAndSlotNumberIgnoreCase(area.getId(), number)) {
                throw new ApiException(ErrorCode.PARKING_SLOT_TAKEN);
            }
            changes.put("number", slot.getSlotNumber() + " -> " + number);
            slot.setSlotNumber(number);
        }
        if (request.vehicleType() != null) {
            requireAreaAllows(area, request.vehicleType());
            slot.setVehicleType(request.vehicleType());
            changes.put("vehicleType", request.vehicleType().name());
        }
        if (request.usage() != null) {
            slot.setUsage(request.usage());
            changes.put("usage", request.usage().name());
        }
        if (request.operationalStatus() != null) {
            slot.setOperationalStatus(request.operationalStatus());
            slot.setOutOfServiceReason(request.operationalStatus() == SlotOperationalStatus.OUT_OF_SERVICE
                    ? Strings.trimToNull(request.outOfServiceReason()) : null);
            changes.put("operationalStatus", request.operationalStatus().name());
        }
        if (request.notes() != null) {
            slot.setNotes(Strings.trimToNull(request.notes()));
        }
        saveSlot(slot);
        audit.record(societyId, "PARKING_SLOT_UPDATED", "PARKING_SLOT", id, "Slot " + slot.getSlotNumber() + " updated",
                changes);
        return slotResponse(slot, statesOf(societyId, List.of(slot)), refLoader.refs());
    }

    /** Admins and vehicle guards. {@code areaId} lists a whole area (default page size up to 500). */
    @Transactional(readOnly = true)
    public PageResponse<SlotResponse> listSlots(UUID areaId, ParkingUsage usage, SlotOperationalStatus status,
                                                VehicleType vehicleType, String q, Integer page, Integer size) {
        UUID societyId = requireSlotReader();
        int pageSize = size == null ? (areaId != null ? MAX_SLOT_PAGE : Pages.DEFAULT_SIZE) : Math.min(Math.max(size, 1), MAX_SLOT_PAGE);
        int pageNumber = page == null || page < 0 ? 0 : page;
        String like = Strings.likePattern(q);
        Page<ParkingSlot> result = slots.search(societyId, areaId, usage, status, vehicleType,
                like == null ? null : like.toUpperCase(),
                org.springframework.data.domain.PageRequest.of(pageNumber, pageSize, Sort.by("slotNumber")));
        Map<UUID, SlotState> states = statesOf(societyId, result.getContent());
        ParkingRefLoader.Refs refs = refLoader.refs()
                .preloadAreas(result.getContent().stream().map(ParkingSlot::getAreaId).toList());
        return PageResponse.of(result, s -> slotResponse(s, states, refs));
    }

    @Transactional(readOnly = true)
    public SlotResponse getSlot(UUID id) {
        UUID societyId = requireSlotReader();
        ParkingSlot slot = requireSlot(id, societyId);
        return slotResponse(slot, statesOf(societyId, List.of(slot)), refLoader.refs());
    }

    private UUID requireSlotReader() {
        UUID societyId = access.societyId();
        if (access.core().hasPermission(societyId, Permission.PARKING_INVENTORY_MANAGE)
                || access.core().hasPermission(societyId, Permission.PARKING_LOG_VIEW)) {
            return societyId;
        }
        access.requireVehicleGuard();
        return societyId;
    }

    // ========================================================== availability

    @Transactional(readOnly = true)
    public AvailabilityResponse availability(ParkingUsage usage, VehicleType vehicleType, Instant from, Instant until) {
        UUID societyId = access.requireViewer();
        ParkingUsage effectiveUsage = usage == null ? ParkingUsage.VISITOR : usage;
        VehicleType type = vehicleType == null ? VehicleType.CAR : vehicleType;
        Instant start = from == null ? clock.instant() : from;
        Instant end = until == null ? start.plus(Duration.ofHours(4)) : until;
        validateWindow(start, end);
        Computation result = compute(societyId, effectiveUsage.servedBy(), type, start, end, null);
        SocietyParkingSettings settings = settingsStore.settingsFor(societyId);
        boolean paidAlternative = effectiveUsage != ParkingUsage.PAID && settings.isPaidParkingEnabled()
                && !compute(societyId, ParkingUsage.PAID.servedBy(), type, start, end, null).free().isEmpty();

        ParkingRefLoader.Refs refs = refLoader.refs().preloadAreas(result.candidates().stream().map(ParkingSlot::getAreaId).toList());
        Map<UUID, int[]> perArea = new LinkedHashMap<>();
        Set<UUID> freeIds = result.free().stream().map(ParkingSlot::getId).collect(Collectors.toSet());
        for (ParkingSlot slot : result.candidates()) {
            int[] counts = perArea.computeIfAbsent(slot.getAreaId(), k -> new int[2]);
            counts[1]++;
            if (freeIds.contains(slot.getId())) {
                counts[0]++;
            }
        }
        List<AreaAvailability> byArea = perArea.entrySet().stream()
                .map(e -> new AreaAvailability(refs.areaRefOf(e.getKey()), e.getValue()[0], e.getValue()[1]))
                .toList();
        return new AvailabilityResponse(effectiveUsage, type, start, end, result.free().size(),
                result.candidates().size(), byArea, result.nextAvailableAt(), paidAlternative);
    }

    public static void validateWindow(Instant from, Instant until) {
        if (!until.isAfter(from)) {
            throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "until", "The end time must be after the start time.");
        }
        if (Duration.between(from, until).compareTo(MAX_AVAILABILITY_WINDOW) > 0) {
            throw ApiException.field(ErrorCode.INVALID_PARKING_WINDOW, "until", "The window can be at most 31 days.");
        }
    }

    /** Candidate slots (in service, active area, usage, vehicle type) and the ones free for the whole window. */
    public record Computation(List<ParkingSlot> candidates, List<ParkingSlot> free, Instant nextAvailableAt) {
    }

    @Transactional(readOnly = true)
    public Computation compute(UUID societyId, Set<ParkingUsage> usages, VehicleType vehicleType, Instant from,
                               Instant until, UUID areaId) {
        List<ParkingSlot> candidates = slots.findCandidates(societyId, usages, areaId).stream()
                .filter(s -> vehicleType == null || vehicleType.fits(s.getVehicleType()))
                .toList();
        if (candidates.isEmpty()) {
            return new Computation(List.of(), List.of(), null);
        }
        Instant end = until == null ? TimeRanges.MAX : until;
        List<UUID> ids = candidates.stream().map(ParkingSlot::getId).toList();
        Map<UUID, List<Instant>> blockedUntil = new HashMap<>();
        Set<UUID> blockedForever = new java.util.HashSet<>();
        for (ParkingAllocation a : allocations.findOverlappingAny(ids, from, end)) {
            block(a.getSlotId(), a.getValidUntil(), blockedUntil, blockedForever);
        }
        for (ParkingSession s : sessions.findActiveInSlots(ids)) {
            if (s.getValidUntil() == null || s.getValidUntil().isAfter(from)) {
                block(s.getSlotId(), s.getValidUntil(), blockedUntil, blockedForever);
            }
        }
        List<ParkingSlot> free = candidates.stream()
                .filter(s -> !blockedUntil.containsKey(s.getId()) && !blockedForever.contains(s.getId()))
                .toList();
        Instant next = null;
        if (free.isEmpty()) {
            next = blockedUntil.entrySet().stream()
                    .filter(e -> !blockedForever.contains(e.getKey()))
                    .map(e -> e.getValue().stream().max(Comparator.naturalOrder()).orElse(null))
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
        }
        return new Computation(candidates, free, next);
    }

    private static void block(UUID slotId, Instant until, Map<UUID, List<Instant>> blockedUntil, Set<UUID> forever) {
        if (until == null) {
            forever.add(slotId);
        } else {
            blockedUntil.computeIfAbsent(slotId, k -> new ArrayList<>()).add(until);
        }
    }

    // ======================================================= slot assignment

    /** Locks and returns the slot (same society). Use before allocating it. */
    public ParkingSlot lockSlot(UUID slotId, UUID societyId) {
        ParkingSlot slot = slots.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ApiException(ErrorCode.PARKING_SLOT_NOT_FOUND));
        access.ensureSameSociety(slot.getSocietyId(), societyId, ErrorCode.PARKING_SLOT_NOT_FOUND);
        return slot;
    }

    /** Slot is in service, its area active, the vehicle fits and the usage is one of {@code usages}. */
    public void requireSuitable(ParkingSlot slot, Set<ParkingUsage> usages, VehicleType vehicleType) {
        ParkingArea area = areas.findById(slot.getAreaId()).orElseThrow(() -> new ApiException(ErrorCode.PARKING_AREA_NOT_FOUND));
        if (!slot.isInService()) {
            throw new ApiException(ErrorCode.SLOT_OUT_OF_SERVICE);
        }
        if (!area.isActive()) {
            throw new ApiException(ErrorCode.PARKING_AREA_INACTIVE);
        }
        if (vehicleType != null && !vehicleType.fits(slot.getVehicleType())) {
            throw new ApiException(ErrorCode.SLOT_NOT_SUITABLE, "Slot " + slot.getSlotNumber() + " is for "
                    + slot.getVehicleType().name().toLowerCase() + "s.");
        }
        if (usages != null && !usages.contains(slot.getUsage())) {
            throw new ApiException(ErrorCode.SLOT_NOT_SUITABLE, "Slot " + slot.getSlotNumber() + " is a "
                    + slot.getUsage().name().toLowerCase() + " slot.");
        }
    }

    /** 409 ALLOCATION_CONFLICT when a live allocation of the (locked) slot overlaps the window. */
    public void requireNoOverlap(ParkingSlot slot, Instant from, Instant until, UUID excludeAllocationId) {
        if (!allocations.findOverlapping(slot.getId(), from, until == null ? TimeRanges.MAX : until,
                excludeAllocationId).isEmpty()) {
            throw new ApiException(ErrorCode.ALLOCATION_CONFLICT);
        }
    }

    /**
     * Finds a free slot for the window and locks it. Concurrent callers are serialised by the row lock and re-check
     * after acquiring it, so two requests never receive the same slot.
     */
    public Optional<ParkingSlot> findAndLockFreeSlot(UUID societyId, Set<ParkingUsage> usages, VehicleType vehicleType,
                                                     Instant from, Instant until, UUID preferredAreaId) {
        List<ParkingSlot> ordered = new ArrayList<>();
        if (preferredAreaId != null) {
            ordered.addAll(compute(societyId, usages, vehicleType, from, until, preferredAreaId).free());
        }
        compute(societyId, usages, vehicleType, from, until, null).free().stream()
                .filter(s -> !ordered.contains(s)).forEach(ordered::add);
        Instant end = until == null ? TimeRanges.MAX : until;
        for (ParkingSlot candidate : ordered) {
            ParkingSlot locked = lockSlot(candidate.getId(), societyId);
            boolean overlap = !allocations.findOverlapping(locked.getId(), from, end, null).isEmpty();
            boolean occupied = sessions.findActiveInSlots(List.of(locked.getId())).stream()
                    .anyMatch(s -> s.getValidUntil() == null || s.getValidUntil().isAfter(from));
            if (!overlap && !occupied && locked.isInService()) {
                return Optional.of(locked);
            }
        }
        return Optional.empty();
    }

    /** True when an active session other than {@code excludeSessionId} is parked in the slot. */
    public boolean isOccupied(UUID slotId) {
        return !sessions.findActiveInSlots(List.of(slotId)).isEmpty();
    }

    // ================================================================ states

    /** Live state of each slot: allocation covering now, active session, operational status. */
    public record SlotState(ParkingSlot slot, ParkingArea area, ParkingAllocation allocation, ParkingSession session) {

        public boolean blocked() {
            return !slot.isInService() || area == null || !area.isActive();
        }

        public boolean occupied() {
            return session != null;
        }

        public boolean allocated() {
            return allocation != null;
        }

        /** Society-reserved capacity, or held by an allocation. */
        public boolean reserved() {
            return slot.getUsage() == ParkingUsage.RESERVED || allocated();
        }

        public SlotStatus status() {
            if (blocked()) {
                return SlotStatus.BLOCKED;
            }
            if (occupied()) {
                return SlotStatus.OCCUPIED;
            }
            return reserved() ? SlotStatus.RESERVED : SlotStatus.AVAILABLE;
        }
    }

    public Map<UUID, SlotState> statesOf(UUID societyId, Collection<ParkingSlot> slotList) {
        if (slotList.isEmpty()) {
            return Map.of();
        }
        Instant now = clock.instant();
        List<UUID> ids = slotList.stream().map(ParkingSlot::getId).toList();
        Map<UUID, ParkingArea> areaById = new HashMap<>();
        areas.findAllById(slotList.stream().map(ParkingSlot::getAreaId).collect(Collectors.toSet()))
                .forEach(a -> areaById.put(a.getId(), a));
        Map<UUID, ParkingAllocation> allocationBySlot = new HashMap<>();
        for (ParkingAllocation a : allocations.findOverlappingAny(ids, now, now.plusMillis(1))) {
            allocationBySlot.merge(a.getSlotId(), a, (x, y) -> x.getStatus().ordinal() <= y.getStatus().ordinal() ? x : y);
        }
        Map<UUID, ParkingSession> sessionBySlot = sessions.findActiveInSlots(ids).stream()
                .collect(Collectors.toMap(ParkingSession::getSlotId, s -> s, (a, b) -> a));
        Map<UUID, SlotState> result = new HashMap<>();
        for (ParkingSlot slot : slotList) {
            result.put(slot.getId(), new SlotState(slot, areaById.get(slot.getAreaId()),
                    allocationBySlot.get(slot.getId()), sessionBySlot.get(slot.getId())));
        }
        return result;
    }

    // =============================================================== mapping

    private AreaResponse areaResponse(ParkingArea area, Map<UUID, SlotState> states) {
        List<SlotState> inArea = states.values().stream().filter(s -> s.slot().getAreaId().equals(area.getId())).toList();
        int available = (int) inArea.stream().filter(s -> s.status() == SlotStatus.AVAILABLE).count();
        int occupied = (int) inArea.stream().filter(SlotState::occupied).count();
        int outOfService = (int) inArea.stream().filter(s -> !s.slot().isInService()).count();
        return new AreaResponse(area.getId(), area.getCode(), area.getName(), area.getLevel(), area.getUsage(),
                area.allowedTypes().stream().sorted().toList(), inArea.size(), available, occupied, outOfService,
                area.getUsage() == ParkingUsage.RESERVED, area.getDescription(), area.getStatus(), area.getCreatedAt(),
                area.getUpdatedAt());
    }

    private SlotResponse slotResponse(ParkingSlot slot, Map<UUID, SlotState> states, ParkingRefLoader.Refs refs) {
        SlotState state = states.get(slot.getId());
        ParkingArea area = state != null && state.area() != null ? state.area() : refs.area(slot.getAreaId());
        ParkingAllocation allocation = state == null ? null : state.allocation();
        AllocationSummary summary = null;
        if (allocation != null) {
            String number = allocation.getVehicleId() == null ? null
                    : Optional.ofNullable(refs.vehicleEntity(allocation.getVehicleId()))
                            .map(v -> v.getRegistrationNumber()).orElse(null);
            summary = new AllocationSummary(allocation.getId(), allocation.getAllocationType(), allocation.getStatus(),
                    refs.flat(allocation.getFlatId()), number, allocation.getValidFrom(), allocation.getValidUntil());
        }
        boolean blocked = state == null ? !slot.isInService() : state.blocked();
        SlotStatus status = state == null ? (blocked ? SlotStatus.BLOCKED : SlotStatus.AVAILABLE) : state.status();
        return new SlotResponse(slot.getId(), slot.getSlotNumber(), ParkingRefLoader.areaRef(area),
                slot.getVehicleType().accepts().stream().sorted().toList(), slot.getVehicleType(), slot.getUsage(),
                status, slot.getOperationalStatus(), state != null && state.allocated(),
                state != null && state.occupied(), state != null ? state.reserved() : slot.getUsage() == ParkingUsage.RESERVED,
                state != null && state.session() != null ? state.session().getVehicleNumber() : null, summary,
                slot.getOutOfServiceReason(), slot.getNotes(), slot.getCreatedAt(), slot.getUpdatedAt());
    }

    // =============================================================== helpers

    public ParkingArea requireArea(UUID id, UUID societyId) {
        ParkingArea area = areas.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PARKING_AREA_NOT_FOUND));
        access.ensureSameSociety(area.getSocietyId(), societyId, ErrorCode.PARKING_AREA_NOT_FOUND);
        return area;
    }

    public ParkingSlot requireSlot(UUID id, UUID societyId) {
        ParkingSlot slot = slots.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PARKING_SLOT_NOT_FOUND));
        access.ensureSameSociety(slot.getSocietyId(), societyId, ErrorCode.PARKING_SLOT_NOT_FOUND);
        return slot;
    }

    private ParkingSlot newSlot(ParkingArea area, String number, VehicleType vehicleType, ParkingUsage usage) {
        requireAreaAllows(area, vehicleType);
        if (slots.existsByAreaIdAndSlotNumberIgnoreCase(area.getId(), number)) {
            throw ApiException.field(ErrorCode.PARKING_SLOT_TAKEN, "number", "Slot " + number + " already exists in this area.");
        }
        ParkingSlot slot = new ParkingSlot();
        slot.setSocietyId(area.getSocietyId());
        slot.setAreaId(area.getId());
        slot.setSlotNumber(number);
        slot.setVehicleType(vehicleType);
        slot.setUsage(usage == null ? area.getUsage() : usage);
        slot.setOperationalStatus(SlotOperationalStatus.ACTIVE);
        return slot;
    }

    private static void requireAreaAllows(ParkingArea area, VehicleType type) {
        if (!area.allowedTypes().contains(type)) {
            throw ApiException.field(ErrorCode.SLOT_NOT_SUITABLE, "vehicleType",
                    "Area " + area.getCode() + " does not allow " + type.name().toLowerCase() + " slots.");
        }
        if (area.getStatus() != ActiveStatus.ACTIVE) {
            throw new ApiException(ErrorCode.PARKING_AREA_INACTIVE);
        }
    }

    private void saveArea(ParkingArea area) {
        try {
            areas.saveAndFlush(area);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.PARKING_AREA_TAKEN);
        }
    }

    private void saveSlot(ParkingSlot slot) {
        try {
            slots.saveAndFlush(slot);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.PARKING_SLOT_TAKEN);
        }
    }

    private static List<String> sortedNames(Set<VehicleType> types) {
        return types.stream().filter(Objects::nonNull).sorted().map(Enum::name).toList();
    }
}
