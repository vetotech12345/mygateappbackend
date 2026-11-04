package com.mygateapp.deliveries.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.deliveries.dto.DeliveryDtos;
import com.mygateapp.deliveries.entity.Delivery;
import com.mygateapp.deliveries.service.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deliveries")
@Tag(name = "Deliveries", description = "Delivery agents at the gate, resident approval and entry/exit")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('RESIDENT','GUARD','SOCIETY_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "List deliveries. scope: PENDING (awaiting resident), ACTIVE (approved or inside), HISTORY (default)")
    public PageResponse<DeliveryDtos.DeliveryResponse> list(
            @RequestParam(required = false) Delivery.Scope scope,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) UUID flatId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return deliveryService.list(scope, q, from, flatId, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('RESIDENT','GUARD','SOCIETY_ADMIN','SUPER_ADMIN')")
    public DeliveryDtos.DeliveryResponse get(@PathVariable UUID id) {
        return deliveryService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Guard registers a delivery; the backend decides PENDING or APPROVED from the category rule")
    public DeliveryDtos.DeliveryResponse create(@Valid @RequestBody DeliveryDtos.CreateDeliveryRequest request) {
        return deliveryService.register(request);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('RESIDENT')")
    public DeliveryDtos.DeliveryResponse approve(@PathVariable UUID id) {
        return deliveryService.decide(id, DeliveryService.Decision.APPROVE);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('RESIDENT')")
    public DeliveryDtos.DeliveryResponse reject(@PathVariable UUID id) {
        return deliveryService.decide(id, DeliveryService.Decision.REJECT);
    }

    @PostMapping("/{id}/leave-at-gate")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Resident asks security to keep the parcel at the gate")
    public DeliveryDtos.DeliveryResponse leaveAtGate(@PathVariable UUID id) {
        return deliveryService.decide(id, DeliveryService.Decision.LEAVE_AT_GATE);
    }

    @PostMapping("/{id}/entry")
    @PreAuthorize("hasRole('GUARD')")
    public DeliveryDtos.DeliveryResponse entry(@PathVariable UUID id) {
        return deliveryService.recordEntry(id);
    }

    @PostMapping("/{id}/exit")
    @PreAuthorize("hasRole('GUARD')")
    public DeliveryDtos.DeliveryResponse exit(@PathVariable UUID id) {
        return deliveryService.recordExit(id);
    }
}
