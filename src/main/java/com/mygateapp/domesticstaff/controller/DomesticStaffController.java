package com.mygateapp.domesticstaff.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.common.dto.StatusRequest;
import com.mygateapp.domesticstaff.dto.StaffDtos;
import com.mygateapp.domesticstaff.entity.DomesticStaff;
import com.mygateapp.domesticstaff.service.DomesticStaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/domestic-staff")
@Tag(name = "Domestic staff", description = "Maids, drivers, cooks...: registration, flat assignment and gate attendance")
public class DomesticStaffController {

    private static final String ADMINS = "hasAnyRole('SOCIETY_ADMIN','SUPER_ADMIN')";
    private static final String MEMBERS = "hasAnyRole('RESIDENT','GUARD','SOCIETY_ADMIN','SUPER_ADMIN')";

    private final DomesticStaffService service;

    public DomesticStaffController(DomesticStaffService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(MEMBERS)
    @Operation(summary = "Staff list: residents get staff assigned to their flats; guards and admins the society")
    public PageResponse<StaffDtos.StaffResponse> list(@RequestParam(required = false) String q,
                                                      @RequestParam(required = false) DomesticStaff.Presence presence,
                                                      @RequestParam(required = false) DomesticStaff.Category category,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return service.list(q, presence, category, page, size);
    }

    @GetMapping("/attendance")
    @PreAuthorize(MEMBERS)
    @Operation(summary = "Attendance history (Flutter path), optionally for one staff member")
    public PageResponse<StaffDtos.AttendanceResponse> attendance(
            @RequestParam(required = false) UUID staffId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.attendanceHistory(staffId, from, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize(MEMBERS)
    public StaffDtos.StaffResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/attendance")
    @PreAuthorize(MEMBERS)
    public PageResponse<StaffDtos.AttendanceResponse> attendanceOf(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.attendanceHistory(id, from, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    public StaffDtos.StaffResponse create(@Valid @RequestBody StaffDtos.StaffRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Update a staff member; flatIds replaces the flat assignments when provided")
    public StaffDtos.StaffResponse update(@PathVariable UUID id, @Valid @RequestBody StaffDtos.StaffRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(ADMINS)
    public StaffDtos.StaffResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return service.updateStatus(id, request);
    }

    @PostMapping(path = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMINS)
    @Operation(summary = "Upload the staff photo (multipart part 'photo', JPEG/PNG/WebP, max 5 MB)")
    public StaffDtos.StaffResponse uploadPhoto(@PathVariable UUID id, @RequestPart("photo") MultipartFile photo) {
        return service.uploadPhoto(id, photo);
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Record arrival (guard on duty with STAFF_ATTENDANCE). Notifies residents of the assigned flats.")
    public StaffDtos.StaffResponse checkIn(@PathVariable UUID id) {
        return service.checkIn(id);
    }

    @PostMapping("/{id}/check-out")
    @PreAuthorize("hasRole('GUARD')")
    public StaffDtos.StaffResponse checkOut(@PathVariable UUID id) {
        return service.checkOut(id);
    }
}
