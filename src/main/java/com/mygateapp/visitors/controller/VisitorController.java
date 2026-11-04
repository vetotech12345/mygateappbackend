package com.mygateapp.visitors.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.visitors.dto.VisitorDtos;
import com.mygateapp.visitors.enums.VisitorEnums.DisplayStatus;
import com.mygateapp.visitors.enums.VisitorEnums.Scope;
import com.mygateapp.visitors.enums.VisitorEnums.Source;
import com.mygateapp.visitors.service.VisitorService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Visitors", description = "Walk-in requests, resident approvals, invitations (pre-approval) and gate entry/exit")
public class VisitorController {

    private static final String ANY_MEMBER = "hasAnyRole('RESIDENT','GUARD','SOCIETY_ADMIN','SUPER_ADMIN')";

    private final VisitorService visitorService;

    public VisitorController(VisitorService visitorService) {
        this.visitorService = visitorService;
    }

    @GetMapping("/visitors")
    @PreAuthorize(ANY_MEMBER)
    @Operation(summary = "List visitors. Residents get their flats only; guards and admins the whole society.",
            description = "scope: PENDING (awaiting decision), EXPECTED (approved or valid invite, not yet inside), "
                    + "INSIDE, HISTORY (default). status filters by the Flutter display status.")
    public PageResponse<VisitorDtos.VisitorResponse> list(
            @RequestParam(required = false) Scope scope,
            @RequestParam(required = false) DisplayStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) UUID flatId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return visitorService.list(new VisitorService.ListQuery(scope, status, q, from, flatId, null, page, size));
    }

    @GetMapping("/visitors/{id}")
    @PreAuthorize(ANY_MEMBER)
    public VisitorDtos.VisitorResponse get(@PathVariable UUID id) {
        return visitorService.get(id);
    }

    @PostMapping(path = "/visitors", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Guard registers a walk-in visitor; the flat's residents are asked to approve")
    public VisitorDtos.VisitorResponse create(@Valid @RequestBody VisitorDtos.CreateVisitorRequest request) {
        return visitorService.registerWalkIn(request, null);
    }

    @PostMapping(path = "/visitors", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Same as above with a photo: multipart part 'data' (JSON) + part 'photo' (JPEG/PNG/WebP, max 5 MB)")
    public VisitorDtos.VisitorResponse createWithPhoto(@Valid @RequestPart("data") VisitorDtos.CreateVisitorRequest request,
                                                       @RequestPart(value = "photo", required = false) MultipartFile photo) {
        return visitorService.registerWalkIn(request, photo);
    }

    @PostMapping({"/visitors/invites", "/visitor-invitations"})
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Resident pre-approves a visitor for a time window; returns a 6-digit pass code")
    public VisitorDtos.VisitorResponse invite(@Valid @RequestBody VisitorDtos.InviteVisitorRequest request) {
        return visitorService.invite(request);
    }

    @GetMapping("/visitor-invitations")
    @PreAuthorize(ANY_MEMBER)
    @Operation(summary = "Invitations (pre-approved visitors) visible to the caller")
    public PageResponse<VisitorDtos.VisitorResponse> invitations(@RequestParam(required = false) Scope scope,
                                                                 @RequestParam(required = false) Integer page,
                                                                 @RequestParam(required = false) Integer size) {
        return visitorService.list(new VisitorService.ListQuery(scope, null, null, null, null, Source.INVITATION, page, size));
    }

    @PostMapping("/visitors/{id}/approve")
    @PreAuthorize("hasRole('RESIDENT')")
    public VisitorDtos.VisitorResponse approve(@PathVariable UUID id) {
        return visitorService.approve(id);
    }

    @PostMapping("/visitors/{id}/reject")
    @PreAuthorize("hasRole('RESIDENT')")
    public VisitorDtos.VisitorResponse reject(@PathVariable UUID id,
                                              @Valid @RequestBody(required = false) VisitorDtos.RejectRequest request) {
        return visitorService.reject(id, request == null ? null : request.reason());
    }

    @PostMapping("/visitors/{id}/cancel")
    @PreAuthorize("hasAnyRole('RESIDENT','GUARD')")
    @Operation(summary = "Guard: cancel a pending walk-in. Resident: cancel an upcoming invite.")
    public VisitorDtos.VisitorResponse cancel(@PathVariable UUID id) {
        return visitorService.cancel(id);
    }

    @PostMapping("/visitors/{id}/entry")
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Record entry. Only approved visitors, or invites inside their window. Duplicate -> 409.")
    public VisitorDtos.VisitorResponse entry(@PathVariable UUID id) {
        return visitorService.recordEntry(id);
    }

    @PostMapping("/visitors/{id}/exit")
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Record exit. Only visitors inside; second call -> 409 VISITOR_ALREADY_EXITED.")
    public VisitorDtos.VisitorResponse exit(@PathVariable UUID id) {
        return visitorService.recordExit(id);
    }

    @PostMapping("/visitors/verify-pass")
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Find a valid invitation by its 6-digit pass code")
    public VisitorDtos.VisitorResponse verifyPass(@Valid @RequestBody VisitorDtos.VerifyPassRequest request) {
        return visitorService.verifyPass(request.passCode());
    }

    @GetMapping("/guards/pending-visitors")
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Walk-ins waiting for a resident decision (same as GET /visitors?scope=PENDING)")
    public PageResponse<VisitorDtos.VisitorResponse> pending(@RequestParam(required = false) Integer page,
                                                             @RequestParam(required = false) Integer size) {
        return visitorService.list(new VisitorService.ListQuery(Scope.PENDING, null, null, null, null, null, page, size));
    }

    @GetMapping("/guards/active-visitors")
    @PreAuthorize("hasRole('GUARD')")
    @Operation(summary = "Visitors currently inside (same as GET /visitors?scope=INSIDE)")
    public PageResponse<VisitorDtos.VisitorResponse> active(@RequestParam(required = false) Integer page,
                                                            @RequestParam(required = false) Integer size) {
        return visitorService.list(new VisitorService.ListQuery(Scope.INSIDE, null, null, null, null, null, page, size));
    }
}
