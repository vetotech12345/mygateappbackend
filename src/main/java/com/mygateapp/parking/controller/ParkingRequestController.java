package com.mygateapp.parking.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.parking.dto.ParkingDtos;
import com.mygateapp.parking.enums.ParkingEnums.RequestStatus;
import com.mygateapp.parking.enums.ParkingEnums.RequestType;
import com.mygateapp.parking.service.ParkingRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
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
@Tag(name = "Parking requests", description = "Additional slots, temporary and paid permits, visitor parking; approvals")
public class ParkingRequestController {

    private static final String RESIDENTS_AND_ADMINS = "hasAnyRole('RESIDENT','SOCIETY_ADMIN','SUPER_ADMIN')";

    private final ParkingRequestService requests;

    public ParkingRequestController(ParkingRequestService requests) {
        this.requests = requests;
    }

    @GetMapping("/api/v1/parking/requests/mine")
    @PreAuthorize("hasRole('RESIDENT')")
    public PageResponse<ParkingDtos.RequestResponse> mine(@RequestParam(required = false) RequestStatus status,
                                                          @RequestParam(required = false) RequestType type,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        return requests.mine(status, type, page, size);
    }

    @GetMapping("/api/v1/parking/requests")
    @PreAuthorize(RESIDENTS_AND_ADMINS)
    @Operation(summary = "Admins: all requests of the society. Residents: their own.")
    public PageResponse<ParkingDtos.RequestResponse> list(@RequestParam(required = false) RequestStatus status,
                                                          @RequestParam(required = false) RequestType type,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        return requests.list(status, type, false, page, size);
    }

    @GetMapping("/api/v1/parking/requests/{id}")
    @PreAuthorize(RESIDENTS_AND_ADMINS)
    public ParkingDtos.RequestResponse get(@PathVariable UUID id) {
        return requests.get(id);
    }

    @PostMapping("/api/v1/parking/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "ADDITIONAL_SLOT, TEMPORARY_PERMIT or PAID_PERMIT for the caller's flat. 409 REQUEST_ALREADY_PENDING.")
    public ParkingDtos.RequestResponse create(@Valid @RequestBody ParkingDtos.CreateRequestRequest request) {
        return requests.create(request);
    }

    @PostMapping("/api/v1/parking/requests/{id}/cancel")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Cancel a pending request, or approved visitor parking that has not been used")
    public ParkingDtos.RequestResponse cancel(@PathVariable UUID id) {
        return requests.cancel(id);
    }

    @PostMapping({"/api/v1/parking/requests/{id}/approve", "/api/v1/parking/visitor-requests/{id}/approve"})
    @PreAuthorize(ParkingInventoryController.ADMINS)
    @Operation(summary = "Approve: allocates the given slot (or a free one) and issues the permit. PAID_PERMIT creates "
            + "a pending payment; the permit activates once it is paid.")
    public ParkingDtos.RequestResponse approve(@PathVariable UUID id,
                                               @Valid @RequestBody(required = false) ParkingDtos.DecideRequest request) {
        return requests.approve(id, request);
    }

    @PostMapping({"/api/v1/parking/requests/{id}/reject", "/api/v1/parking/visitor-requests/{id}/reject"})
    @PreAuthorize(ParkingInventoryController.ADMINS)
    @Operation(summary = "Reject with remarks")
    public ParkingDtos.RequestResponse reject(@PathVariable UUID id, @Valid @RequestBody ParkingDtos.DecideRequest request) {
        return requests.reject(id, request.remarks());
    }

    // --------------------------------------------------------- visitor parking

    @PostMapping("/api/v1/parking/visitor-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Visitor parking for the caller's flat. visitorId links an existing visitor of the flat. "
            + "Auto-approved with a slot when the society allows it; 409 PARKING_UNAVAILABLE when nothing is free.")
    public ParkingDtos.RequestResponse createVisitor(@Valid @RequestBody ParkingDtos.CreateVisitorParkingRequest request) {
        return requests.createVisitor(request);
    }

    @GetMapping("/api/v1/parking/visitor-requests")
    @PreAuthorize(RESIDENTS_AND_ADMINS)
    public PageResponse<ParkingDtos.RequestResponse> visitorRequests(@RequestParam(required = false) RequestStatus status,
                                                                     @RequestParam(required = false) Integer page,
                                                                     @RequestParam(required = false) Integer size) {
        return requests.list(status, RequestType.VISITOR_SLOT, true, page, size);
    }

    @GetMapping("/api/v1/parking/visitor-requests/{id}")
    @PreAuthorize(RESIDENTS_AND_ADMINS)
    public ParkingDtos.RequestResponse visitorRequest(@PathVariable UUID id) {
        return requests.get(id);
    }
}
