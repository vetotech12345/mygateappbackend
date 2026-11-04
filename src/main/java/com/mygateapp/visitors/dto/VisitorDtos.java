package com.mygateapp.visitors.dto;

import com.mygateapp.common.dto.Refs;
import com.mygateapp.common.gate.MovementStatus;
import com.mygateapp.users.dto.UserDtos;
import com.mygateapp.visitors.enums.VisitorEnums;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class VisitorDtos {

    public static final String VEHICLE_PATTERN = "^$|^[A-Za-z0-9 -]{4,13}$";

    private VisitorDtos() {
    }

    @Schema(description = "Walk-in visitor registered by a guard. The gate comes from the guard's assignment.")
    public record CreateVisitorRequest(
            @Schema(example = "Vikram Iyer") @NotBlank(message = "Name is required")
            @Size(min = 2, max = 60, message = "Name must be 2-60 characters")
            @Pattern(regexp = UserDtos.NAME_PATTERN, message = "Name can only contain letters") String name,
            @Schema(example = "9822011122") @NotBlank(message = "Enter a mobile number") String mobile,
            @NotNull(message = "Visitor type is required") VisitorEnums.VisitorType type,
            @NotNull(message = "Select a destination flat") UUID flatId,
            @Schema(example = "Family visit") @NotBlank(message = "Purpose is required")
            @Size(max = 120, message = "Purpose must be under 120 characters") String purpose,
            @Pattern(regexp = VEHICLE_PATTERN, message = "Enter a valid vehicle number") String vehicleNumber,
            @Min(1) @Max(20) Integer guestCount,
            @Size(max = 200) String notes) {
    }

    @Schema(description = "Pre-approval created by a resident; the response contains a 6-digit passCode to share.")
    public record InviteVisitorRequest(
            @NotBlank(message = "Name is required") @Size(min = 2, max = 60, message = "Name must be 2-60 characters")
            @Pattern(regexp = UserDtos.NAME_PATTERN, message = "Name can only contain letters") String name,
            @NotBlank(message = "Enter a mobile number") String mobile,
            @NotNull(message = "Visitor type is required") VisitorEnums.VisitorType type,
            @NotNull(message = "Select a flat") UUID flatId,
            @NotBlank(message = "Purpose is required") @Size(max = 120) String purpose,
            @NotNull(message = "validFrom is required") Instant validFrom,
            @NotNull(message = "validUntil is required") Instant validUntil,
            @Min(1) @Max(20) Integer guestCount,
            @Pattern(regexp = VEHICLE_PATTERN, message = "Enter a valid vehicle number") String vehicleNumber) {
    }

    public record RejectRequest(@Size(max = 200, message = "Reason must be under 200 characters") String reason) {
    }

    public record VerifyPassRequest(
            @NotBlank(message = "Enter the 6-digit invite code")
            @Pattern(regexp = "^\\d{6}$", message = "Enter the 6-digit invite code") String passCode) {
    }

    /** Matches the Flutter {@code VisitorDto} plus the precise approval / movement fields. */
    public record VisitorResponse(
            UUID id,
            String name,
            String mobile,
            VisitorEnums.VisitorType type,
            String photoUrl,
            Refs.FlatRef flat,
            Refs.GateRef gate,
            String purpose,
            VisitorEnums.DisplayStatus status,
            VisitorEnums.ApprovalStatus approvalStatus,
            MovementStatus movementStatus,
            VisitorEnums.Source source,
            Instant requestedAt,
            Instant decidedAt,
            String decidedBy,
            String rejectionReason,
            Instant entryAt,
            Instant exitAt,
            String vehicleNumber,
            String createdBy,
            String passCode,
            Instant validFrom,
            Instant validUntil,
            int guestCount,
            String notes,
            List<Refs.TimelineEvent> history) {
    }
}
