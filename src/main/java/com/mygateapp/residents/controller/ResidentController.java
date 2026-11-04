package com.mygateapp.residents.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.residents.dto.ResidentDtos;
import com.mygateapp.residents.entity.FlatMembership;
import com.mygateapp.residents.service.ResidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/residents")
@Tag(name = "Residents", description = "Residents (flat memberships), approvals, move-outs and family members")
public class ResidentController {

    private static final String ADMINS = "hasAnyRole('SOCIETY_ADMIN','SUPER_ADMIN')";

    private final ResidentService residentService;

    public ResidentController(ResidentService residentService) {
        this.residentService = residentService;
    }

    @GetMapping
    @PreAuthorize(ADMINS)
    @Operation(summary = "Search residents of a society (name, mobile or flat number) with pagination")
    public PageResponse<ResidentDtos.ResidentResponse> search(@RequestParam(required = false) UUID societyId,
                                                              @RequestParam(required = false) FlatMembership.Status status,
                                                              @RequestParam(required = false) UUID towerId,
                                                              @RequestParam(required = false) UUID flatId,
                                                              @RequestParam(required = false) String q,
                                                              @RequestParam(required = false) Integer page,
                                                              @RequestParam(required = false) Integer size,
                                                              @RequestParam(required = false) String sort) {
        return residentService.search(societyId, status, towerId, flatId, q, page, size, sort);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SOCIETY_ADMIN','SUPER_ADMIN','RESIDENT')")
    @Operation(summary = "Register a resident for a flat",
            description = "Admins create ACTIVE residents. A flat owner may add a co-resident or tenant, which stays PENDING "
                    + "until an admin approves it.")
    public ResidentDtos.ResidentResponse create(@Valid @RequestBody ResidentDtos.CreateResidentRequest request) {
        return residentService.create(request);
    }

    @GetMapping("/me")
    @Operation(summary = "The caller's flats (active and pending requests) with family members")
    public ResidentDtos.MyResidentProfile me() {
        return residentService.me();
    }

    @GetMapping("/me/family-members")
    @PreAuthorize("hasRole('RESIDENT')")
    public List<ResidentDtos.FamilyMemberResponse> family() {
        return residentService.myFamily();
    }

    @PostMapping("/me/family-members")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Add a household member without a login (shown in the resident profile)")
    public ResidentDtos.FamilyMemberResponse addFamilyMember(@Valid @RequestBody ResidentDtos.AddFamilyMemberRequest request) {
        return residentService.addFamilyMember(request);
    }

    @DeleteMapping("/me/family-members/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('RESIDENT')")
    public void removeFamilyMember(@PathVariable UUID id) {
        residentService.removeFamilyMember(id);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One resident membership. Admins of the society or the resident themself.")
    public ResidentDtos.ResidentResponse get(@PathVariable UUID id) {
        return residentService.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(ADMINS)
    public ResidentDtos.ResidentResponse update(@PathVariable UUID id, @Valid @RequestBody ResidentDtos.UpdateResidentRequest request) {
        return residentService.update(id, request);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(ADMINS)
    public ResidentDtos.ResidentResponse approve(@PathVariable UUID id) {
        return residentService.approve(id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(ADMINS)
    public ResidentDtos.ResidentResponse reject(@PathVariable UUID id, @Valid @RequestBody(required = false) ResidentDtos.DecisionRequest request) {
        return residentService.reject(id, request);
    }

    @PostMapping("/{id}/end")
    @PreAuthorize(ADMINS)
    @Operation(summary = "End an active membership (move-out). Kept as occupancy history.")
    public ResidentDtos.ResidentResponse end(@PathVariable UUID id, @Valid @RequestBody(required = false) ResidentDtos.EndResidencyRequest request) {
        return residentService.end(id, request);
    }

    @PostMapping("/{id}/transfer")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Move a resident to another flat of the same society")
    public ResidentDtos.ResidentResponse transfer(@PathVariable UUID id, @Valid @RequestBody ResidentDtos.TransferRequest request) {
        return residentService.transfer(id, request);
    }
}
