package com.mygateapp.notices.dto;

import com.mygateapp.notices.entity.Notice;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class NoticeDtos {

    private NoticeDtos() {
    }

    public record NoticeRequest(
            @Schema(description = "Defaults to the caller's society (create only)") UUID societyId,
            @Schema(example = "Water supply interruption") @NotBlank(message = "Title is required") @Size(max = 150) String title,
            @NotBlank(message = "Message is required") @Size(max = 5000) String body,
            @NotNull(message = "Category is required") Notice.Category category,
            Boolean important,
            @Schema(description = "ALL (residents and guards), RESIDENTS (default), GUARDS, TOWERS (residents of towerIds)")
            Notice.Audience audience,
            Set<UUID> towerIds,
            @Size(max = 80) String postedBy,
            @Schema(description = "Publish immediately (create only)") Boolean publish) {
    }

    public record AttachmentResponse(UUID id, String name, String url, String size) {
    }

    /** Matches the Flutter {@code NoticeDto}; {@code audience} is a display label. */
    public record NoticeResponse(
            UUID id,
            String title,
            String body,
            Notice.Category category,
            Instant publishedAt,
            String postedBy,
            boolean important,
            boolean read,
            String audience,
            Notice.Audience audienceType,
            Set<UUID> towerIds,
            Notice.Status status,
            List<AttachmentResponse> attachments,
            Instant createdAt,
            Instant updatedAt) {
    }
}
