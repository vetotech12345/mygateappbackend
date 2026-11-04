package com.mygateapp.complaints.dto;

import com.mygateapp.common.dto.Refs;
import com.mygateapp.complaints.entity.Complaint;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ComplaintDtos {

    private ComplaintDtos() {
    }

    public record CreateComplaintRequest(
            @NotNull(message = "Category is required") Complaint.Category category,
            @Schema(example = "Lift not working") @NotBlank(message = "Title is required")
            @Size(min = 5, max = 80, message = "Title must be 5-80 characters") String title,
            @NotBlank(message = "Description is required")
            @Size(min = 10, max = 1000, message = "Describe the issue in 10-1000 characters") String description,
            @NotNull(message = "flatId is required") UUID flatId) {
    }

    @Schema(description = "Residents may edit title/description/category of their OPEN complaints. Admins may also "
            + "assign the complaint to a society administrator and set remarks.")
    public record UpdateComplaintRequest(
            Complaint.Category category,
            @Size(min = 5, max = 80, message = "Title must be 5-80 characters") String title,
            @Size(min = 10, max = 1000, message = "Describe the issue in 10-1000 characters") String description,
            UUID assignedToUserId,
            @Size(max = 500) String adminRemarks) {
    }

    public record StatusChangeRequest(@NotNull(message = "status is required") Complaint.Status status,
                                      @Size(max = 500) String remarks) {
    }

    public record CommentRequest(@NotBlank(message = "Comment is required") @Size(max = 1000) String body,
                                 @Schema(description = "Admin-only note hidden from residents") Boolean internal) {
    }

    public record CommentResponse(UUID id, String authorName, String body, boolean internal, Instant createdAt) {
    }

    /** Matches the Flutter {@code ComplaintDto}. */
    public record ComplaintResponse(
            UUID id,
            String ticketNumber,
            Complaint.Category category,
            String title,
            String description,
            List<String> attachments,
            Instant createdAt,
            Instant updatedAt,
            Complaint.Status status,
            String adminRemarks,
            Refs.FlatRef flat,
            Refs.UserRef raisedBy,
            Refs.UserRef assignedTo,
            Instant resolvedAt,
            Instant closedAt,
            List<Refs.TimelineEvent> history,
            List<CommentResponse> comments) {
    }
}
