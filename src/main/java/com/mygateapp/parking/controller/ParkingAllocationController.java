package com.mygateapp.parking.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.parking.dto.ParkingDtos;
import com.mygateapp.parking.enums.ParkingEnums.AllocationStatus;
import com.mygateapp.parking.enums.ParkingEnums.AllocationType;
import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.enums.ParkingEnums.PermitType;
import com.mygateapp.parking.service.ParkingAllocationService;
import com.mygateapp.parkingpayment.dto.PaymentDtos;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Parking allocations & permits", description = "Slot allocations (permanent, temporary, transfer, cancel) and permits")
public class ParkingAllocationController {

    private final ParkingAllocationService allocations;

    public ParkingAllocationController(ParkingAllocationService allocations) {
        this.allocations = allocations;
    }

    @GetMapping("/api/v1/parking/allocations/mine")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Allocations of the caller's flats (live, plus those ended in the last 30 days)")
    public List<ParkingDtos.AllocationResponse> mine() {
        return allocations.mine();
    }

    @GetMapping("/api/v1/parking/allocations")
    @PreAuthorize(ParkingInventoryController.ADMINS)
    public PageResponse<ParkingDtos.AllocationResponse> list(@RequestParam(required = false) AllocationStatus status,
                                                             @RequestParam(required = false) AllocationType type,
                                                             @RequestParam(required = false) UUID flatId,
                                                             @RequestParam(required = false) UUID slotId,
                                                             @RequestParam(required = false) UUID vehicleId,
                                                             @RequestParam(required = false) Integer page,
                                                             @RequestParam(required = false) Integer size) {
        return allocations.list(status, type, flatId, slotId, vehicleId, page, size);
    }

    @GetMapping("/api/v1/parking/allocations/{id}")
    @PreAuthorize("hasAnyRole('RESIDENT','SOCIETY_ADMIN','SUPER_ADMIN')")
    public ParkingDtos.AllocationResponse get(@PathVariable UUID id) {
        return allocations.get(id);
    }

    @PostMapping("/api/v1/parking/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ParkingInventoryController.ADMINS)
    @Operation(summary = "Allocate a slot. 409 ALLOCATION_CONFLICT when the slot (or the vehicle) is already allocated "
            + "for an overlapping period. Issues a permit for the vehicle unless issuePermit=false.")
    public ParkingDtos.AllocationResponse create(@Valid @RequestBody ParkingDtos.CreateAllocationRequest request) {
        return allocations.create(request);
    }

    @PatchMapping("/api/v1/parking/allocations/{id}")
    @PreAuthorize(ParkingInventoryController.ADMINS)
    @Operation(summary = "Extend/shorten (validUntil), change vehicle, suspend / resume / revoke (status)")
    public ParkingDtos.AllocationResponse update(@PathVariable UUID id,
                                                 @Valid @RequestBody ParkingDtos.UpdateAllocationRequest request) {
        return allocations.update(id, request);
    }

    @PostMapping("/api/v1/parking/allocations/{id}/transfer")
    @PreAuthorize(ParkingInventoryController.ADMINS)
    @Operation(summary = "Move an allocation to another slot (and optionally flat / vehicle) atomically")
    public ParkingDtos.AllocationResponse transfer(@PathVariable UUID id,
                                                   @Valid @RequestBody ParkingDtos.TransferAllocationRequest request) {
        return allocations.transfer(id, request);
    }

    // ----------------------------------------------------------------- permits

    @GetMapping("/api/v1/parking/permits/mine")
    @PreAuthorize("hasRole('RESIDENT')")
    public List<ParkingDtos.PermitResponse> myPermits() {
        return allocations.myPermits();
    }

    @GetMapping("/api/v1/parking/permits")
    @PreAuthorize(ParkingInventoryController.ADMINS)
    public PageResponse<ParkingDtos.PermitResponse> permits(@RequestParam(required = false) PermitStatus status,
                                                            @RequestParam(required = false) PermitType type,
                                                            @RequestParam(required = false) String q,
                                                            @RequestParam(required = false) Integer page,
                                                            @RequestParam(required = false) Integer size) {
        return allocations.listPermits(status, type, q, page, size);
    }

    @GetMapping("/api/v1/parking/permits/{id}")
    @PreAuthorize(ParkingInventoryController.MEMBERS)
    public ParkingDtos.PermitResponse permit(@PathVariable UUID id) {
        return allocations.getPermit(id);
    }

    @PostMapping("/api/v1/parking/permits")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ParkingInventoryController.ADMINS)
    @Operation(summary = "Issue a permit (resident, temporary, visitor, society, staff, service, emergency). With slotId "
            + "the slot is allocated too. PAID_MONTHLY permits come from paid permit requests.")
    public ParkingDtos.PermitResponse issue(@Valid @RequestBody ParkingDtos.IssuePermitRequest request) {
        return allocations.issuePermit(request);
    }

    @PostMapping("/api/v1/parking/permits/{id}/revoke")
    @PreAuthorize(ParkingInventoryController.ADMINS)
    public ParkingDtos.PermitResponse revoke(@PathVariable UUID id, @Valid @RequestBody PaymentDtos.ReasonRequest request) {
        return allocations.revokePermit(id, request.reason());
    }
}
