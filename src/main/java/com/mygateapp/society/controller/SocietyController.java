package com.mygateapp.society.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.society.dto.SocietyDtos;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.service.SocietyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/societies")
@Tag(name = "Societies", description = "Society management (platform admins) and society configuration (society admins)")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','SOCIETY_ADMIN')")
public class SocietyController {

    private final SocietyService societyService;

    public SocietyController(SocietyService societyService) {
        this.societyService = societyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Create a society (platform admin). Society codes are unique.")
    public SocietyDtos.SocietyResponse create(@Valid @RequestBody SocietyDtos.CreateSocietyRequest request) {
        return societyService.create(request);
    }

    @GetMapping
    @Operation(summary = "List societies: all for platform admins, assigned societies for society admins")
    public PageResponse<SocietyDtos.SocietyResponse> list(@RequestParam(required = false) String q,
                                                          @RequestParam(required = false) Society.Status status,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size,
                                                          @RequestParam(required = false) String sort) {
        return societyService.list(q, status, page, size, sort);
    }

    @GetMapping("/{id}")
    public SocietyDtos.SocietyResponse get(@PathVariable UUID id) {
        return societyService.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update society details (the code cannot be changed)")
    public SocietyDtos.SocietyResponse update(@PathVariable UUID id, @Valid @RequestBody SocietyDtos.UpdateSocietyRequest request) {
        return societyService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Activate / deactivate a society and manage its subscription. Members of an inactive society lose access.")
    public SocietyDtos.SocietyResponse updateStatus(@PathVariable UUID id,
                                                    @Valid @RequestBody SocietyDtos.UpdateSocietyStatusRequest request) {
        return societyService.updateStatus(id, request);
    }

    @GetMapping("/{id}/settings")
    public SocietyDtos.SettingsDto settings(@PathVariable UUID id) {
        return societyService.getSettings(id);
    }

    @PutMapping("/{id}/settings")
    @Operation(summary = "Update visitor / delivery rules of the society")
    public SocietyDtos.SettingsDto updateSettings(@PathVariable UUID id, @Valid @RequestBody SocietyDtos.SettingsDto request) {
        return societyService.updateSettings(id, request);
    }

    @GetMapping("/{id}/admins")
    public List<SocietyDtos.MemberResponse> admins(@PathVariable UUID id) {
        return societyService.listAdmins(id);
    }

    @PostMapping("/{id}/admins")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Make a person (by mobile number) an administrator of the society; creates the user if needed")
    public SocietyDtos.MemberResponse addAdmin(@PathVariable UUID id, @Valid @RequestBody SocietyDtos.AddSocietyAdminRequest request) {
        return societyService.addAdmin(id, request);
    }

    @DeleteMapping("/{id}/admins/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void removeAdmin(@PathVariable UUID id, @PathVariable UUID userId) {
        societyService.removeAdmin(id, userId);
    }
}
