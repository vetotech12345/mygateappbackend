package com.mygateapp.vehicle.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.parking.dto.ParkingDtos.VehicleSearchResult;
import com.mygateapp.parking.enums.ParkingEnums.SearchField;
import com.mygateapp.parking.service.ParkingSessionQueryService;
import com.mygateapp.vehicle.dto.VehicleDtos;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.RegistrationStatus;
import com.mygateapp.vehicle.service.VehicleService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vehicles")
@Tag(name = "Vehicles", description = "Vehicle registry: resident, society, staff, service, emergency and paid vehicles")
public class VehicleController {

    private static final String ADMINS = "hasAnyRole('SOCIETY_ADMIN','SUPER_ADMIN')";
    private static final String RESIDENTS_AND_ADMINS = "hasAnyRole('RESIDENT','SOCIETY_ADMIN','SUPER_ADMIN')";

    private final VehicleService vehicles;
    private final ParkingSessionQueryService queries;

    public VehicleController(VehicleService vehicles, ParkingSessionQueryService queries) {
        this.vehicles = vehicles;
        this.queries = queries;
    }

    @GetMapping
    @PreAuthorize(RESIDENTS_AND_ADMINS)
    @Operation(summary = "Residents: vehicles of their flats. Admins: all vehicles of the society (filters).")
    public PageResponse<VehicleDtos.VehicleResponse> list(@RequestParam(required = false) String q,
                                                          @RequestParam(required = false) Category category,
                                                          @RequestParam(required = false) RegistrationStatus status,
                                                          @RequestParam(required = false) UUID flatId,
                                                          @RequestParam(required = false) Boolean active,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size,
                                                          @RequestParam(required = false) String sort) {
        return vehicles.list(q, category, status, flatId, active, page, size, sort);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('GUARD','SOCIETY_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Search by vehicle, flat, slot or permit (same as GET /parking/vehicles/search). No contact data.")
    public List<VehicleSearchResult> search(@RequestParam String q, @RequestParam(required = false) SearchField by,
                                            @RequestParam(required = false) Category category) {
        return queries.search(q, by, category);
    }

    @GetMapping("/{id}")
    @PreAuthorize(RESIDENTS_AND_ADMINS)
    public VehicleDtos.VehicleResponse get(@PathVariable UUID id) {
        return vehicles.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(RESIDENTS_AND_ADMINS)
    @Operation(summary = "Register a vehicle. 409 VEHICLE_ALREADY_REGISTERED, 422 VEHICLE_LIMIT_REACHED. "
            + "Registration never grants gate access by itself.")
    public VehicleDtos.VehicleResponse create(@Valid @RequestBody VehicleDtos.CreateVehicleRequest request) {
        return vehicles.create(request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize(RESIDENTS_AND_ADMINS)
    @Operation(summary = "Edit / deactivate. A resident changing the number or type of an approved vehicle sends it "
            + "back to PENDING and suspends its slot and permits until re-approved.")
    public VehicleDtos.VehicleResponse update(@PathVariable UUID id, @Valid @RequestBody VehicleDtos.UpdateVehicleRequest request) {
        return vehicles.update(id, request);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Send a saved (NOT_SUBMITTED) or REJECTED vehicle for approval")
    public VehicleDtos.VehicleResponse submit(@PathVariable UUID id) {
        return vehicles.submit(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Approve a pending, rejected or suspended vehicle (reinstates suspended slots and permits)")
    public VehicleDtos.VehicleResponse approve(@PathVariable UUID id,
                                               @Valid @RequestBody(required = false) VehicleDtos.ReviewRequest request) {
        return vehicles.approve(id, request == null ? null : request.remarks());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Reject a pending vehicle; remarks are required")
    public VehicleDtos.VehicleResponse reject(@PathVariable UUID id, @Valid @RequestBody VehicleDtos.ReviewRequest request) {
        return vehicles.reject(id, request.remarks());
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Suspend an approved vehicle (its slots and permits are suspended); remarks are required")
    public VehicleDtos.VehicleResponse suspend(@PathVariable UUID id, @Valid @RequestBody VehicleDtos.ReviewRequest request) {
        return vehicles.suspend(id, request.remarks());
    }
}
