package com.mygateapp.flats.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.Pages;
import com.mygateapp.common.Strings;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.common.dto.Refs;
import com.mygateapp.common.dto.StatusRequest;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.flats.dto.FlatDtos;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.flats.entity.Tower;
import com.mygateapp.flats.entity.Wing;
import com.mygateapp.flats.repository.FlatRepository;
import com.mygateapp.flats.repository.TowerRepository;
import com.mygateapp.flats.repository.WingRepository;
import com.mygateapp.residents.entity.FlatMembership;
import com.mygateapp.residents.repository.FlatMembershipRepository;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.Permission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Society structure: Society -> Tower -> (Wing) -> floor -> Flat. */
@Service
public class FlatService {

    private static final Set<String> FLAT_SORT_FIELDS = Set.of("flatNumber", "floorNumber", "createdAt");
    private static final int MAX_BULK_FLATS = 2000;

    private final TowerRepository towers;
    private final WingRepository wings;
    private final FlatRepository flats;
    private final FlatMembershipRepository memberships;
    private final AccessService access;
    private final AuditService audit;

    public FlatService(TowerRepository towers, WingRepository wings, FlatRepository flats,
                       FlatMembershipRepository memberships, AccessService access, AuditService audit) {
        this.towers = towers;
        this.wings = wings;
        this.flats = flats;
        this.memberships = memberships;
        this.access = access;
        this.audit = audit;
    }

    // -------------------------------------------------------------------- towers

    @Transactional
    public FlatDtos.TowerResponse createTower(FlatDtos.CreateTowerRequest request) {
        UUID societyId = access.resolveSocietyId(request.societyId());
        access.require(societyId, Permission.STRUCTURE_MANAGE);
        String code = request.code().trim().toUpperCase();
        if (towers.existsBySocietyIdAndCode(societyId, code)) {
            throw ApiException.field(ErrorCode.TOWER_CODE_TAKEN, "code", ErrorCode.TOWER_CODE_TAKEN.defaultMessage());
        }
        Tower tower = new Tower();
        tower.setSocietyId(societyId);
        tower.setCode(code);
        tower.setName(request.name().trim());
        tower.setFloorCount(request.floorCount());
        towers.save(tower);
        audit.record(societyId, "TOWER_CREATED", "TOWER", tower.getId(), "Tower " + code + " created", Map.of("name", tower.getName()));
        return FlatDtos.TowerResponse.of(tower, List.of());
    }

    @Transactional(readOnly = true)
    public List<FlatDtos.TowerResponse> listTowers(UUID societyIdParam) {
        UUID societyId = access.resolveSocietyId(societyIdParam);
        access.require(societyId, Permission.SOCIETY_VIEW);
        return towers.findBySocietyIdOrderByCode(societyId).stream()
                .map(t -> FlatDtos.TowerResponse.of(t, wings.findByTowerIdOrderByName(t.getId())))
                .toList();
    }

    @Transactional
    public FlatDtos.TowerResponse updateTower(UUID towerId, FlatDtos.UpdateTowerRequest request) {
        Tower tower = requireTower(towerId);
        access.require(tower.getSocietyId(), Permission.STRUCTURE_MANAGE);
        String code = request.code().trim().toUpperCase();
        if (!code.equals(tower.getCode()) && towers.existsBySocietyIdAndCode(tower.getSocietyId(), code)) {
            throw ApiException.field(ErrorCode.TOWER_CODE_TAKEN, "code", ErrorCode.TOWER_CODE_TAKEN.defaultMessage());
        }
        tower.setCode(code);
        tower.setName(request.name().trim());
        tower.setFloorCount(request.floorCount());
        if (request.status() != null) {
            tower.setStatus(request.status());
        }
        audit.record(tower.getSocietyId(), "TOWER_UPDATED", "TOWER", towerId, "Tower " + code + " updated",
                Map.of("status", tower.getStatus().name()));
        return FlatDtos.TowerResponse.of(tower, wings.findByTowerIdOrderByName(towerId));
    }

    @Transactional
    public FlatDtos.WingResponse createWing(UUID towerId, FlatDtos.CreateWingRequest request) {
        Tower tower = requireTower(towerId);
        access.require(tower.getSocietyId(), Permission.STRUCTURE_MANAGE);
        String name = request.name().trim();
        if (wings.existsByTowerIdAndName(towerId, name)) {
            throw ApiException.field(ErrorCode.WING_NAME_TAKEN, "name", ErrorCode.WING_NAME_TAKEN.defaultMessage());
        }
        Wing wing = new Wing();
        wing.setSocietyId(tower.getSocietyId());
        wing.setTowerId(towerId);
        wing.setName(name);
        wings.save(wing);
        audit.record(tower.getSocietyId(), "WING_CREATED", "WING", wing.getId(),
                "Wing " + name + " created in tower " + tower.getCode(), Map.of());
        return FlatDtos.WingResponse.of(wing);
    }

    @Transactional(readOnly = true)
    public List<FlatDtos.WingResponse> listWings(UUID towerId) {
        Tower tower = requireTower(towerId);
        access.require(tower.getSocietyId(), Permission.SOCIETY_VIEW);
        return wings.findByTowerIdOrderByName(towerId).stream().map(FlatDtos.WingResponse::of).toList();
    }

    // --------------------------------------------------------------------- flats

    @Transactional
    public FlatDtos.FlatResponse createFlat(FlatDtos.CreateFlatRequest request) {
        Tower tower = requireTower(request.towerId());
        access.require(tower.getSocietyId(), Permission.STRUCTURE_MANAGE);
        Wing wing = wingOf(tower, request.wingId());
        String number = request.flatNumber().trim().toUpperCase();
        if (flats.numberTaken(tower.getId(), wing == null ? null : wing.getId(), number, null)) {
            throw ApiException.field(ErrorCode.FLAT_NUMBER_TAKEN, "flatNumber", ErrorCode.FLAT_NUMBER_TAKEN.defaultMessage());
        }
        Flat flat = newFlat(tower, wing, request.floorNumber(), number, request.flatType(), request.areaSqft());
        try {
            flats.saveAndFlush(flat);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.field(ErrorCode.FLAT_NUMBER_TAKEN, "flatNumber", ErrorCode.FLAT_NUMBER_TAKEN.defaultMessage());
        }
        audit.record(tower.getSocietyId(), "FLAT_CREATED", "FLAT", flat.getId(), "Flat " + flat.toRef().label() + " created", Map.of());
        return FlatDtos.FlatResponse.of(flat, List.of());
    }

    @Transactional
    public FlatDtos.BulkCreateFlatsResponse bulkCreate(FlatDtos.BulkCreateFlatsRequest request) {
        Tower tower = requireTower(request.towerId());
        access.require(tower.getSocietyId(), Permission.STRUCTURE_MANAGE);
        Wing wing = wingOf(tower, request.wingId());
        if (request.toFloor() < request.fromFloor()) {
            throw ApiException.field("toFloor", "toFloor must not be below fromFloor");
        }
        int total = (request.toFloor() - request.fromFloor() + 1) * request.unitsPerFloor();
        if (total > MAX_BULK_FLATS) {
            throw ApiException.field("unitsPerFloor", "At most " + MAX_BULK_FLATS + " flats can be created at once");
        }
        Set<String> existing = new HashSet<>(flats.findNumbers(tower.getId(), wing == null ? null : wing.getId()));
        List<String> skipped = new ArrayList<>();
        int created = 0;
        for (int floor = request.fromFloor(); floor <= request.toFloor(); floor++) {
            for (int unit = 1; unit <= request.unitsPerFloor(); unit++) {
                String number = flatNumber(floor, unit);
                if (!existing.add(number)) {
                    skipped.add(number);
                    continue;
                }
                flats.save(newFlat(tower, wing, floor, number, request.flatType(), null));
                created++;
            }
        }
        flats.flush();
        audit.record(tower.getSocietyId(), "FLATS_BULK_CREATED", "TOWER", tower.getId(),
                created + " flats created in tower " + tower.getCode(),
                Map.of("fromFloor", request.fromFloor(), "toFloor", request.toFloor(), "unitsPerFloor", request.unitsPerFloor()));
        return new FlatDtos.BulkCreateFlatsResponse(created, skipped.size(), skipped);
    }

    @Transactional(readOnly = true)
    public PageResponse<FlatDtos.FlatResponse> listFlats(UUID societyIdParam, UUID towerId, UUID wingId, ActiveStatus status,
                                                         String q, Integer page, Integer size, String sort) {
        UUID societyId = access.resolveSocietyId(societyIdParam);
        access.require(societyId, Permission.SOCIETY_VIEW);
        var pageable = Pages.of(page, size, sort, FLAT_SORT_FIELDS, Sort.by("tower.code", "floorNumber", "flatNumber"));
        return PageResponse.of(flats.search(societyId, towerId, wingId, status, Strings.likePattern(q), pageable),
                flat -> FlatDtos.FlatResponse.of(flat, null));
    }

    /** Admins see occupants; a resident may read their own flat. */
    @Transactional(readOnly = true)
    public FlatDtos.FlatResponse getFlat(UUID flatId) {
        Flat flat = requireFlat(flatId);
        if (access.hasPermission(flat.getSocietyId(), Permission.RESIDENT_VIEW)) {
            List<FlatDtos.OccupantView> occupants = memberships.findHistoryForFlat(flatId).stream()
                    .filter(m -> m.getStatus() == FlatMembership.Status.ACTIVE || m.getStatus() == FlatMembership.Status.PENDING)
                    .map(m -> new FlatDtos.OccupantView(m.getId(), m.getUser().getId(), m.getUser().getName(),
                            m.getOccupancy().name(), m.getStatus().name(), m.isPrimary()))
                    .toList();
            return FlatDtos.FlatResponse.of(flat, occupants);
        }
        if (access.isResidentOf(flatId)) {
            return FlatDtos.FlatResponse.of(flat, null);
        }
        throw new ApiException(ErrorCode.ACCESS_DENIED);
    }

    @Transactional
    public FlatDtos.FlatResponse updateFlat(UUID flatId, FlatDtos.UpdateFlatRequest request) {
        Flat flat = requireFlat(flatId);
        access.require(flat.getSocietyId(), Permission.STRUCTURE_MANAGE);
        Wing wing = wingOf(flat.getTower(), request.wingId());
        String number = request.flatNumber().trim().toUpperCase();
        if (flats.numberTaken(flat.getTower().getId(), wing == null ? null : wing.getId(), number, flatId)) {
            throw ApiException.field(ErrorCode.FLAT_NUMBER_TAKEN, "flatNumber", ErrorCode.FLAT_NUMBER_TAKEN.defaultMessage());
        }
        String before = flat.toRef().label();
        flat.setWing(wing);
        flat.setFloorNumber(request.floorNumber());
        flat.setFlatNumber(number);
        flat.setFlatType(Strings.trimToNull(request.flatType()));
        flat.setAreaSqft(request.areaSqft());
        audit.record(flat.getSocietyId(), "FLAT_UPDATED", "FLAT", flatId, "Flat " + before + " updated",
                Map.of("label", flat.toRef().label()));
        return FlatDtos.FlatResponse.of(flat, null);
    }

    @Transactional
    public FlatDtos.FlatResponse updateFlatStatus(UUID flatId, StatusRequest request) {
        Flat flat = requireFlat(flatId);
        access.require(flat.getSocietyId(), Permission.STRUCTURE_MANAGE);
        ActiveStatus previous = flat.getStatus();
        flat.setStatus(request.status());
        audit.record(flat.getSocietyId(), "FLAT_STATUS_CHANGED", "FLAT", flatId,
                "Flat " + flat.toRef().label() + " " + previous + " -> " + request.status(),
                Map.of("reason", request.reason() == null ? "" : request.reason()));
        return FlatDtos.FlatResponse.of(flat, null);
    }

    @Transactional(readOnly = true)
    public List<FlatDtos.OccupancyRecord> occupancyHistory(UUID flatId) {
        Flat flat = requireFlat(flatId);
        access.require(flat.getSocietyId(), Permission.RESIDENT_VIEW);
        return memberships.findHistoryForFlat(flatId).stream()
                .map(m -> new FlatDtos.OccupancyRecord(m.getId(), m.getUser().getId(), m.getUser().getName(),
                        m.getOccupancy().name(), m.getStatus().name(), m.getStartDate(), m.getEndDate(), m.getEndReason(),
                        m.getCreatedAt()))
                .toList();
    }

    /** {@code GET /society/flats}: active flats of the caller's society for pickers (guards, residents, admins). */
    @Transactional(readOnly = true)
    public List<Refs.FlatRef> societyFlats() {
        UUID societyId = access.currentSocietyId();
        return flats.findAllForPicker(societyId, ActiveStatus.ACTIVE).stream().map(Flat::toRef).toList();
    }

    // ------------------------------------------------------------------- helpers

    /** Loads a flat visible to the caller; flats of other societies are reported as not found. */
    public Flat requireFlat(UUID flatId) {
        Flat flat = flats.findById(flatId).orElseThrow(() -> new ApiException(ErrorCode.FLAT_NOT_FOUND));
        access.ensureInScope(flat.getSocietyId(), ErrorCode.FLAT_NOT_FOUND);
        return flat;
    }

    private Tower requireTower(UUID towerId) {
        Tower tower = towers.findById(towerId).orElseThrow(() -> new ApiException(ErrorCode.TOWER_NOT_FOUND));
        access.ensureInScope(tower.getSocietyId(), ErrorCode.TOWER_NOT_FOUND);
        return tower;
    }

    private Wing wingOf(Tower tower, UUID wingId) {
        if (wingId == null) {
            return null;
        }
        Wing wing = wings.findById(wingId).orElseThrow(() -> ApiException.field(ErrorCode.WING_NOT_FOUND, "wingId",
                ErrorCode.WING_NOT_FOUND.defaultMessage()));
        if (!wing.getTowerId().equals(tower.getId())) {
            throw ApiException.field(ErrorCode.WING_NOT_FOUND, "wingId", "This wing does not belong to the tower.");
        }
        return wing;
    }

    private static Flat newFlat(Tower tower, Wing wing, int floor, String number, String flatType, Integer area) {
        Flat flat = new Flat();
        flat.setSocietyId(tower.getSocietyId());
        flat.setTower(tower);
        flat.setWing(wing);
        flat.setFloorNumber(floor);
        flat.setFlatNumber(number);
        flat.setFlatType(Strings.trimToNull(flatType));
        flat.setAreaSqft(area);
        return flat;
    }

    static String flatNumber(int floor, int unit) {
        if (floor > 0) {
            return String.valueOf(floor * 100 + unit);
        }
        if (floor == 0) {
            return "G%02d".formatted(unit);
        }
        return "B%d%02d".formatted(-floor, unit);
    }
}
