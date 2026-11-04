package com.mygateapp.guards.controller;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.common.dto.StatusRequest;
import com.mygateapp.guards.dto.GuardDtos;
import com.mygateapp.guards.service.GateService;
import com.mygateapp.guards.service.GuardService;
import com.mygateapp.users.dto.UserMeResponse;
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
@Tag(name = "Guards & gates", description = "Security guards, gate assignments, shifts and gates")
public class GuardController {

    private static final String ADMINS = "hasAnyRole('SOCIETY_ADMIN','SUPER_ADMIN')";

    private final GuardService guardService;
    private final GateService gateService;

    public GuardController(GuardService guardService, GateService gateService) {
        this.guardService = guardService;
        this.gateService = gateService;
    }

    @PostMapping("/guards")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    @Operation(summary = "Register a guard in a society (creates the user by mobile number if needed)")
    public GuardDtos.GuardResponse create(@Valid @RequestBody GuardDtos.CreateGuardRequest request) {
        return guardService.create(request);
    }

    @GetMapping("/guards")
    @PreAuthorize(ADMINS)
    public PageResponse<GuardDtos.GuardResponse> list(@RequestParam(required = false) UUID societyId,
                                                      @RequestParam(required = false) ActiveStatus status,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size,
                                                      @RequestParam(required = false) String sort) {
        return guardService.list(societyId, status, q, page, size, sort);
    }

    @PostMapping("/guards/me/duty")
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Start or end the caller's shift. Returns the refreshed /users/me.")
    public UserMeResponse duty(@Valid @RequestBody GuardDtos.DutyRequest request) {
        return guardService.setDuty(request.onDuty());
    }

    @GetMapping("/guards/{id}")
    @Operation(summary = "Guard details (admins, or the guard themself)")
    public GuardDtos.GuardResponse get(@PathVariable UUID id) {
        return guardService.get(id);
    }

    @PutMapping("/guards/{id}")
    @PreAuthorize(ADMINS)
    public GuardDtos.GuardResponse update(@PathVariable UUID id, @Valid @RequestBody GuardDtos.UpdateGuardRequest request) {
        return guardService.update(id, request);
    }

    @PatchMapping("/guards/{id}/status")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Activate / deactivate a guard. Inactive guards lose all gate access.")
    public GuardDtos.GuardResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return guardService.updateStatus(id, request);
    }

    @PostMapping("/guards/{id}/assignments")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Assign the guard to a gate and shift (replaces the current assignment)")
    public GuardDtos.GuardResponse assign(@PathVariable UUID id, @Valid @RequestBody GuardDtos.AssignGateRequest request) {
        return guardService.assignGate(id, request);
    }

    @GetMapping("/guards/{id}/assignments")
    @PreAuthorize(ADMINS)
    public List<GuardDtos.AssignmentView> assignments(@PathVariable UUID id) {
        return guardService.assignmentHistory(id);
    }

    @GetMapping("/guards/{id}/activity")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Everything the guard recorded: visitor/delivery events, staff attendance and duty periods")
    public PageResponse<GuardDtos.GuardActivityItem> activity(@PathVariable UUID id,
                                                              @RequestParam(required = false) Integer page,
                                                              @RequestParam(required = false) Integer size) {
        return guardService.activity(id, page, size);
    }

    @PostMapping("/gates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    public GuardDtos.GateResponse createGate(@Valid @RequestBody GuardDtos.CreateGateRequest request) {
        return gateService.create(request);
    }

    @GetMapping("/gates")
    @PreAuthorize(ADMINS)
    public List<GuardDtos.GateResponse> gates(@RequestParam(required = false) UUID societyId) {
        return gateService.list(societyId);
    }

    @PutMapping("/gates/{id}")
    @PreAuthorize(ADMINS)
    public GuardDtos.GateResponse updateGate(@PathVariable UUID id, @Valid @RequestBody GuardDtos.UpdateGateRequest request) {
        return gateService.update(id, request);
    }

    @PatchMapping("/gates/{id}/status")
    @PreAuthorize(ADMINS)
    public GuardDtos.GateResponse updateGateStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return gateService.updateStatus(id, request);
    }
}
