package com.mygateapp.notices.controller;

import com.mygateapp.common.dto.PageResponse;
import com.mygateapp.notices.dto.NoticeDtos;
import com.mygateapp.notices.entity.Notice;
import com.mygateapp.notices.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/notices")
@Tag(name = "Notices", description = "Society announcements: admin publishing, member reading and read receipts")
public class NoticeController {

    private static final String ADMINS = "hasAnyRole('SOCIETY_ADMIN','SUPER_ADMIN')";
    private static final String MEMBERS = "hasAnyRole('RESIDENT','GUARD','SOCIETY_ADMIN','SUPER_ADMIN')";

    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @GetMapping
    @PreAuthorize(MEMBERS)
    @Operation(summary = "Published notices for the caller's society and audience; admins may add includeDrafts=true")
    public PageResponse<NoticeDtos.NoticeResponse> list(@RequestParam(required = false) Notice.Category category,
                                                        @RequestParam(defaultValue = "false") boolean unread,
                                                        @RequestParam(defaultValue = "false") boolean includeDrafts,
                                                        @RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        return noticeService.list(category, unread, includeDrafts, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize(MEMBERS)
    public NoticeDtos.NoticeResponse get(@PathVariable UUID id) {
        return noticeService.get(id);
    }

    @PostMapping("/{id}/read")
    @PreAuthorize(MEMBERS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark a notice as read for the caller (idempotent)")
    public void markRead(@PathVariable UUID id) {
        noticeService.markRead(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINS)
    @Operation(summary = "Create a notice (draft, or published immediately with publish=true)")
    public NoticeDtos.NoticeResponse create(@Valid @RequestBody NoticeDtos.NoticeRequest request) {
        return noticeService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(ADMINS)
    public NoticeDtos.NoticeResponse update(@PathVariable UUID id, @Valid @RequestBody NoticeDtos.NoticeRequest request) {
        return noticeService.update(id, request);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize(ADMINS)
    @Operation(summary = "Publish a draft and notify its audience")
    public NoticeDtos.NoticeResponse publish(@PathVariable UUID id) {
        return noticeService.publish(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(ADMINS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        noticeService.delete(id);
    }

    @PostMapping(path = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMINS)
    @Operation(summary = "Attach a PDF or image (multipart part 'file', optional 'name')")
    public NoticeDtos.NoticeResponse attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                            @RequestParam(required = false) String name) {
        return noticeService.addAttachment(id, file, name);
    }
}
