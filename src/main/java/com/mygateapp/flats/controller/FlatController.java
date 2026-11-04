package com.mygateapp.flats.controller;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.common.dto.Refs;
import com.mygateapp.common.dto.StatusRequest;
import com.mygateapp.flats.dto.FlatDtos;
import com.mygateapp.flats.service.FlatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Towers & flats", description = "Society structure: towers, optional wings, floors and flats")
public class FlatController {

    private static final String ADMINS = "hasAnyRole('SOCIETY_ADMIN','SUPER_ADMIN')";

    private final FlatService flatService;

    public FlatController(FlatService flatService) {
        this.flatService = flatService;
    }

    @PostMapping("/towers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    public FlatDtos.TowerResponse createTower(@Valid @RequestBody FlatDtos.CreateTowerRequest request) {
        return flatService.createTower(request);
    }

    @GetMapping("/towers")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Towers (with wings) of a society")
    public List<FlatDtos.TowerResponse> towers(@RequestParam(required = false) UUID societyId) {
        return flatService.listTowers(societyId);
    }

    @PutMapping("/towers/{id}")
    @PreAuthorize(ADMINS)
    public FlatDtos.TowerResponse updateTower(@PathVariable UUID id, @Valid @RequestBody FlatDtos.UpdateTowerRequest request) {
        return flatService.updateTower(id, request);
    }

    @PostMapping("/towers/{id}/wings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    public FlatDtos.WingResponse createWing(@PathVariable UUID id, @Valid @RequestBody FlatDtos.CreateWingRequest request) {
        return flatService.createWing(id, request);
    }

    @GetMapping("/towers/{id}/wings")
    @PreAuthorize(ADMINS)
    public List<FlatDtos.WingResponse> wings(@PathVariable UUID id) {
        return flatService.listWings(id);
    }

    @PostMapping("/flats")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    @Operation(summary = "Register a flat. Flat numbers are unique within the tower (or wing).")
    public FlatDtos.FlatResponse createFlat(@Valid @RequestBody FlatDtos.CreateFlatRequest request) {
        return flatService.createFlat(request);
    }

    @PostMapping("/flats/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    @Operation(summary = "Create many flats at once (floor x units); existing numbers are skipped")
    public FlatDtos.BulkCreateFlatsResponse bulkCreate(@Valid @RequestBody FlatDtos.BulkCreateFlatsRequest request) {
        return flatService.bulkCreate(request);
    }

    @GetMapping("/flats")
    @PreAuthorize(ADMINS)
    public PageResponse<FlatDtos.FlatResponse> flats(@RequestParam(required = false) UUID societyId,
                                                     @RequestParam(required = false) UUID towerId,
                                                     @RequestParam(required = false) UUID wingId,
                                                     @RequestParam(required = false) ActiveStatus status,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size,
                                                     @RequestParam(required = false) String sort) {
        return flatService.listFlats(societyId, towerId, wingId, status, q, page, size, sort);
    }

    @GetMapping("/flats/{id}")
    @Operation(summary = "Flat details. Admins also get the current occupants; residents may read their own flat.")
    public FlatDtos.FlatResponse flat(@PathVariable UUID id) {
        return flatService.getFlat(id);
    }

    @PutMapping("/flats/{id}")
    @PreAuthorize(ADMINS)
    public FlatDtos.FlatResponse updateFlat(@PathVariable UUID id, @Valid @RequestBody FlatDtos.UpdateFlatRequest request) {
        return flatService.updateFlat(id, request);
    }

    @PatchMapping("/flats/{id}/status")
    @PreAuthorize(ADMINS)
    public FlatDtos.FlatResponse updateFlatStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return flatService.updateFlatStatus(id, request);
    }

    @GetMapping("/flats/{id}/occupancy-history")
    @PreAuthorize(ADMINS)
    public List<FlatDtos.OccupancyRecord> occupancyHistory(@PathVariable UUID id) {
        return flatService.occupancyHistory(id);
    }

    @GetMapping("/society/flats")
    @Operation(summary = "Active flats of the caller's society (destination picker in the guard app). Plain JSON array.")
    public List<Refs.FlatRef> societyFlats() {
        return flatService.societyFlats();
    }
}
