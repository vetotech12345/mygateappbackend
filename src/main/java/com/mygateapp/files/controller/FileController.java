package com.mygateapp.files.controller;

import com.mygateapp.files.entity.StoredFile;
import com.mygateapp.files.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "Files", description = "Signed, expiring download links for uploaded images and documents")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping("/{id}")
    @SecurityRequirements
    @Operation(summary = "Download a file through a signed URL returned by another endpoint (no bearer token needed)")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @RequestParam long expires, @RequestParam String signature) {
        StoredFile file = fileService.verifyAndGet(id, expires, signature);
        byte[] content = fileService.content(file);
        boolean image = file.getContentType().startsWith("image/");
        ContentDisposition disposition = (image ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.getOriginalName() == null ? id.toString() : file.getOriginalName())
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }
}
