package com.mygateapp.parking.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.parking.dto.ParkingDtos;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import com.mygateapp.parking.enums.ParkingEnums.SlotOperationalStatus;
import com.mygateapp.parking.service.ParkingInventoryService;
import com.mygateapp.parking.service.ParkingSettingsService;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Parking inventory", description = "Society parking settings, configuration, areas, slots and availability")
public class ParkingInventoryController {

    static final String ADMINS = "hasAnyRole('SOCIETY_ADMIN','SUPER_ADMIN')";
    static final String MEMBERS = "hasAnyRole('RESIDENT','GUARD','SOCIETY_ADMIN','SUPER_ADMIN')";

    private final ParkingInventoryService inventory;
    private final ParkingSettingsService settings;

    public ParkingInventoryController(ParkingInventoryService inventory, ParkingSettingsService settings) {
        this.inventory = inventory;
        this.settings = settings;
    }

    // --------------------------------------------------------------- settings

    @GetMapping("/api/v1/societies/{id}/parking/settings")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Society parking settings with derived capacity (admins)")
    public ParkingDtos.ParkingSettingsResponse getSettings(@PathVariable UUID id) {
        return settings.get(id);
    }

    @PutMapping("/api/v1/societies/{id}/parking/settings")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Replace society parking settings (PARKING_SETTINGS_MANAGE; audited)")
    public ParkingDtos.ParkingSettingsResponse updateSettings(@PathVariable UUID id,
                                                              @Valid @RequestBody ParkingDtos.ParkingSettingsRequest request) {
        return settings.update(id, request);
    }

    @GetMapping("/api/v1/parking/config")
    @PreAuthorize(MEMBERS)
    @Operation(summary = "Parking rules, switches and areas with live counts for the current society")
    public ParkingDtos.ParkingConfigResponse config() {
        return settings.config();
    }

    // ------------------------------------------------------------------ areas

    @GetMapping("/api/v1/parking/areas")
    @PreAuthorize(MEMBERS)
    public List<ParkingDtos.AreaResponse> areas(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return inventory.listAreas(includeInactive);
    }

    @GetMapping("/api/v1/parking/areas/{id}")
    @PreAuthorize(MEMBERS)
    public ParkingDtos.AreaResponse area(@PathVariable UUID id) {
        return inventory.getArea(id);
    }

    @PostMapping("/api/v1/parking/areas")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    public ParkingDtos.AreaResponse createArea(@Valid @RequestBody ParkingDtos.CreateAreaRequest request) {
        return inventory.createArea(request);
    }

    @PatchMapping("/api/v1/parking/areas/{id}")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Rename, change usage / vehicle types, or deactivate (status INACTIVE) an area")
    public ParkingDtos.AreaResponse updateArea(@PathVariable UUID id, @Valid @RequestBody ParkingDtos.UpdateAreaRequest request) {
        return inventory.updateArea(id, request);
    }

    // ------------------------------------------------------------------ slots

    @GetMapping("/api/v1/parking/slots")
    @PreAuthorize("hasAnyRole('GUARD','SOCIETY_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Slots with live status (AVAILABLE, OCCUPIED, RESERVED, BLOCKED) and the separate allocated / "
            + "occupied / reserved / operational flags. With areaId the default page holds the whole area (max 500).")
    public PageResponse<ParkingDtos.SlotResponse> slots(@RequestParam(required = false) UUID areaId,
                                                        @RequestParam(required = false) ParkingUsage usage,
                                                        @RequestParam(required = false) SlotOperationalStatus operationalStatus,
                                                        @RequestParam(required = false) VehicleType vehicleType,
                                                        @RequestParam(required = false) String q,
                                                        @RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        return inventory.listSlots(areaId, usage, operationalStatus, vehicleType, q, page, size);
    }

    @GetMapping("/api/v1/parking/slots/{id}")
    @PreAuthorize("hasAnyRole('GUARD','SOCIETY_ADMIN','SUPER_ADMIN')")
    public ParkingDtos.SlotResponse slot(@PathVariable UUID id) {
        return inventory.getSlot(id);
    }

    @PostMapping("/api/v1/parking/slots")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    public ParkingDtos.SlotResponse createSlot(@Valid @RequestBody ParkingDtos.CreateSlotRequest request) {
        return inventory.createSlot(request);
    }

    @PostMapping("/api/v1/parking/slots/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    @Operation(summary = "Create up to 500 numbered slots in an area")
    public List<ParkingDtos.SlotResponse> bulkCreateSlots(@Valid @RequestBody ParkingDtos.BulkCreateSlotsRequest request) {
        return inventory.bulkCreateSlots(request);
    }

    @PatchMapping("/api/v1/parking/slots/{id}")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Edit a slot, reserve it (usage RESERVED) or take it out of service")
    public ParkingDtos.SlotResponse updateSlot(@PathVariable UUID id, @Valid @RequestBody ParkingDtos.UpdateSlotRequest request) {
        return inventory.updateSlot(id, request);
    }

    // ----------------------------------------------------------- availability

    @GetMapping("/api/v1/parking/availability")
    @PreAuthorize(MEMBERS)
    @Operation(summary = "Free slots for a usage and vehicle type over [from, until) (defaults: VISITOR, CAR, now, 4 h)")
    public ParkingDtos.AvailabilityResponse availability(
            @RequestParam(required = false) ParkingUsage usage,
            @RequestParam(required = false) VehicleType vehicleType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until) {
        return inventory.availability(usage, vehicleType, from, until);
    }
}
