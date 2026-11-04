package com.mygateapp.complaints.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.complaints.dto.ComplaintDtos;
import com.mygateapp.complaints.entity.Complaint;
import com.mygateapp.complaints.service.ComplaintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
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
@RequestMapping("/api/v1/complaints")
@Tag(name = "Complaints", description = "Resident complaints, admin handling, status history and comments")
@PreAuthorize("hasAnyRole('RESIDENT','SOCIETY_ADMIN','SUPER_ADMIN')")
public class ComplaintController {

    private final ComplaintService complaintService;

    public ComplaintController(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    @GetMapping
    @Operation(summary = "Residents: complaints of their flats. Admins: all complaints of the society.")
    public PageResponse<ComplaintDtos.ComplaintResponse> list(@RequestParam(required = false) Complaint.Status status,
                                                              @RequestParam(required = false) Complaint.Category category,
                                                              @RequestParam(required = false) UUID flatId,
                                                              @RequestParam(required = false) String q,
                                                              @RequestParam(required = false) Integer page,
                                                              @RequestParam(required = false) Integer size,
                                                              @RequestParam(required = false) String sort) {
        return complaintService.list(status, category, flatId, q, page, size, sort);
    }

    @GetMapping("/{id}")
    public ComplaintDtos.ComplaintResponse get(@PathVariable UUID id) {
        return complaintService.get(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('RESIDENT')")
    public ComplaintDtos.ComplaintResponse create(@Valid @RequestBody ComplaintDtos.CreateComplaintRequest request) {
        return complaintService.create(request, null);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Raise a complaint with a photo: multipart part 'data' (JSON) + part 'image'")
    public ComplaintDtos.ComplaintResponse createWithImage(@Valid @RequestPart("data") ComplaintDtos.CreateComplaintRequest request,
                                                           @RequestPart(value = "image", required = false) MultipartFile image) {
        return complaintService.create(request, image);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit an open complaint (resident) or assign / add remarks (admin)")
    public ComplaintDtos.ComplaintResponse update(@PathVariable UUID id,
                                                  @Valid @RequestBody ComplaintDtos.UpdateComplaintRequest request) {
        return complaintService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change status (admin). Residents may only close a RESOLVED complaint. Notifies the flat.")
    public ComplaintDtos.ComplaintResponse changeStatus(@PathVariable UUID id,
                                                        @Valid @RequestBody ComplaintDtos.StatusChangeRequest request) {
        return complaintService.changeStatus(id, request);
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ComplaintDtos.CommentResponse comment(@PathVariable UUID id, @Valid @RequestBody ComplaintDtos.CommentRequest request) {
        return complaintService.addComment(id, request);
    }
}
