package com.mygateapp.parking.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.parking.dto.ParkingDtos;
import com.mygateapp.parking.enums.ParkingEnums.SearchField;
import com.mygateapp.parking.enums.ParkingEnums.SessionScope;
import com.mygateapp.parking.service.ParkingGateService;
import com.mygateapp.parking.service.ParkingSessionQueryService;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Parking gate & sessions", description = "Vehicle verification, entry, exit, sessions, search, dashboards")
public class ParkingGateController {

    private final ParkingGateService gate;
    private final ParkingSessionQueryService queries;

    public ParkingGateController(ParkingGateService gate, ParkingSessionQueryService queries) {
        this.gate = gate;
        this.queries = queries;
    }

    @PostMapping("/api/v1/parking/verifications")
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Check a vehicle at the gate. Returns a single-use verificationId (5 min) and the decision: "
            + "ALLOWED, ALREADY_INSIDE, NEEDS_VISITOR_APPROVAL, NOT_REGISTERED or DENIED (with reasons, paid offer)")
    public ParkingDtos.VerificationResponse verify(@Valid @RequestBody ParkingDtos.VerifyRequest request) {
        return gate.verify(request);
    }

    @PostMapping("/api/v1/parking/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Record entry for a fresh verification (send Idempotency-Key). 409 SESSION_ALREADY_ACTIVE, "
            + "409 VERIFICATION_EXPIRED, 403 ENTRY_NOT_AUTHORIZED, 409 PARKING_UNAVAILABLE / SLOT_OCCUPIED / SLOT_RESERVED")
    public ParkingDtos.SessionResponse enter(@Valid @RequestBody ParkingDtos.EntryRequest request) {
        return gate.enter(request);
    }

    @PostMapping("/api/v1/parking/sessions/{id}/close")
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Record exit (send Idempotency-Key). 409 SESSION_ALREADY_CLOSED, 409 PAYMENT_PENDING")
    public ParkingDtos.SessionResponse close(@PathVariable UUID id) {
        return gate.close(id);
    }

    @PostMapping("/api/v1/parking/sessions/{id}/cancel")
    @PreAuthorize(ParkingInventoryController.ADMINS)
    @Operation(summary = "Admin correction of a wrong entry (kept in history as CANCELLED)")
    public ParkingDtos.SessionResponse cancel(@PathVariable UUID id, @Valid @RequestBody ParkingDtos.CancelSessionRequest request) {
        return gate.cancel(id, request.reason());
    }

    @GetMapping("/api/v1/parking/sessions")
    @PreAuthorize(ParkingInventoryController.MEMBERS)
    @Operation(summary = "Sessions, newest first. Residents see their flats only. scope: MINE | ACTIVE | OVERSTAY | HISTORY")
    public PageResponse<ParkingDtos.SessionResponse> sessions(
            @RequestParam(required = false) SessionScope scope,
            @RequestParam(required = false) UUID vehicleId,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean paid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queries.list(scope, vehicleId, category, q, paid, from, to, page, size);
    }

    @GetMapping("/api/v1/parking/sessions/{id}")
    @PreAuthorize(ParkingInventoryController.MEMBERS)
    public ParkingDtos.SessionResponse session(@PathVariable UUID id) {
        return queries.get(id);
    }

    @GetMapping("/api/v1/parking/vehicles/search")
    @PreAuthorize("hasAnyRole('GUARD','SOCIETY_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Search by VEHICLE (default), FLAT, SLOT or PERMIT; q ≥ 2 characters. Owners are role labels; "
            + "no contact data")
    public List<ParkingDtos.VehicleSearchResult> search(@RequestParam String q, @RequestParam(required = false) SearchField by,
                                                        @RequestParam(required = false) Category category) {
        return queries.search(q, by, category);
    }

    @GetMapping("/api/v1/guards/parking/dashboard")
    @PreAuthorize("hasAnyRole('GUARD','SOCIETY_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Vehicles inside, today's entries/exits, waiting visitor vehicles, overstays, area availability")
    public ParkingDtos.GuardParkingDashboard guardDashboard() {
        return queries.guardDashboard();
    }

    @GetMapping("/api/v1/parking/summary")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Resident parking hub: counts, unpaid amount and alerts")
    public ParkingDtos.ParkingSummary summary() {
        return queries.summary();
    }
}
